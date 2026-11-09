#!/usr/bin/env python3
"""Smart Energy — Windows ESP32 classic flasher (development utility).

No automatic erase-flash. JK firmware can be installed APP-ONLY to 0x10000.
VPN firmware is built locally with the owner's credentials using ESP-IDF 5.3.2.
No personal tokens are uploaded to GitHub or stored in the executable.
"""
from __future__ import annotations

import ipaddress
import io
import json
import os
from pathlib import Path
import queue
import re
import urllib.request
import zipfile
import shutil
import subprocess
import sys
import tempfile
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from typing import Callable

TAILTINY_COMMIT = "d1ec2ae45e252e8dc737452be0f6afe1ac2e3edb"
PRIVATE_NETWORKS = (
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
)


def valid_lan_ip(value: str) -> bool:
    try:
        address = ipaddress.IPv4Address(value)
        return any(address in network for network in PRIVATE_NETWORKS)
    except ValueError:
        return False


def validate_vpn_config(values: dict[str, str]) -> None:
    if not values["wifi_ssid"] or len(values["wifi_ssid"].encode("utf-8")) > 32:
        raise ValueError("SSID Wi-Fi invalide : 1 à 32 octets.")
    if not 8 <= len(values["wifi_password"]) <= 63:
        raise ValueError("Mot de passe Wi-Fi invalide : 8 à 63 caractères.")
    if not values["tailscale_key"].startswith("tskey-auth-"):
        raise ValueError("Indique une clé d'association Tailscale tskey-auth- personnelle.")
    if not 20 <= len(values["gateway_token"]) <= 128:
        raise ValueError("Token VPN : utilise 20 à 128 caractères.")
    for index in range(1, 4):
        host = values[f"jk{index}_ip"].strip()
        code = values[f"jk{index}_code"]
        if index == 1 and not host:
            raise ValueError("Le lecteur JK 1 doit être renseigné.")
        if bool(host) != bool(code):
            raise ValueError(f"ESP JK {index} : renseigne à la fois l'IP et le code.")
        if host and not valid_lan_ip(host):
            raise ValueError(f"ESP JK {index} : l'adresse doit être une IPv4 privée du LAN.")
        if code and not 8 <= len(code) <= 32:
            raise ValueError(f"ESP JK {index} : code personnel invalide (8–32).")
    # Reject syntax injection into sdkconfig / subprocess commands.
    for name, val in values.items():
        if any(c in val for c in ("\r", "\n", "\x00")):
            raise ValueError(f"Caractère interdit dans {name}.")


def sdkconfig_contents(values: dict[str, str]) -> str:
    settings = {
        "ML_WIFI_SSID": values["wifi_ssid"],
        "ML_WIFI_PASSWORD": values["wifi_password"],
        "ML_TAILSCALE_AUTH_KEY": values["tailscale_key"],
        "SE_GATEWAY_TOKEN": values["gateway_token"],
        "SE_JK_IP": values["jk1_ip"],
        "SE_JK_READ_CODE": values["jk1_code"],
        "SE_JK2_IP": values["jk2_ip"],
        "SE_JK2_READ_CODE": values["jk2_code"],
        "SE_JK3_IP": values["jk3_ip"],
        "SE_JK3_READ_CODE": values["jk3_code"],
        "SE_VOLT_IP": "",
        "SE_VOLT_READ_PATH": "",
        "SE_VOLT_READ_CODE": "",
    }
    return "# PRIVATE — NEVER COMMIT OR SEND THIS FILE\n" + "".join(
        f"CONFIG_{key}={json.dumps(value, ensure_ascii=False)}\n"
        for key, value in settings.items()
    )


def valid_serial_port(port: str) -> bool:
    return bool(re.fullmatch(r"COM[1-9][0-9]*", port.strip().upper()))


class Flasher(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("Smart Energy — Flasheur ESP32 (expérimental)")
        self.geometry("840x810")
        self.minsize(740, 620)
        self.events: queue.Queue[tuple[str, str]] = queue.Queue()
        self.busy = False
        self.secret_values: list[str] = []
        self.port = tk.StringVar()
        self.jk_image = tk.StringVar()
        self.jk_mode = tk.StringVar(value="update")
        self.vpn_image = tk.StringVar()
        self.vpn_mode = tk.StringVar(value="factory")
        self.fields: dict[str, tk.StringVar] = {}
        self._build()
        self.after(100, self._drain)

    def _label(self, parent: tk.Widget, text: str, row: int, column: int = 0) -> None:
        ttk.Label(parent, text=text).grid(
            row=row, column=column, sticky="w", padx=8, pady=5
        )

    def _entry(self, parent: tk.Widget, var: tk.StringVar, row: int,
               password: bool = False, width: int = 52) -> ttk.Entry:
        field = ttk.Entry(parent, textvariable=var, width=width,
                          show="•" if password else "")
        field.grid(row=row, column=1, sticky="ew", padx=8, pady=5)
        return field

    def _picker(self, parent: tk.Widget, row: int, var: tk.StringVar,
                title: str, is_folder: bool = False) -> None:
        def choose() -> None:
            result = (filedialog.askdirectory(title=title) if is_folder
                      else filedialog.askopenfilename(title=title))
            if result:
                var.set(result)
        ttk.Button(parent, text="Parcourir…", command=choose).grid(
            row=row, column=2, sticky="w", padx=5
        )

    def _build(self) -> None:
        outer = ttk.Frame(self, padding=12)
        outer.pack(fill="both", expand=True)
        ttk.Label(
            outer, text="ESP32 classique uniquement — ne pas utiliser sur ESP32-S3",
            font=("Segoe UI", 12, "bold")
        ).pack(anchor="w", pady=(0, 8))
        connection = ttk.Frame(outer)
        connection.pack(fill="x")
        self._label(connection, "Port série USB :", 0)
        self._entry(connection, self.port, 0, width=18)
        ttk.Button(connection, text="Détecter", command=self._ports).grid(
            row=0, column=2, padx=6
        )
        tabs = ttk.Notebook(outer)
        tabs.pack(fill="both", expand=True, pady=10)
        jk = ttk.Frame(tabs, padding=12)
        vpn = ttk.Frame(tabs, padding=12)
        jk.columnconfigure(1, weight=1)
        vpn.columnconfigure(1, weight=1)
        tabs.add(jk, text="ESP32 JK")
        tabs.add(vpn, text="ESP32 VPN (3 lecteurs)")
        self._build_jk(jk)
        self._build_vpn(vpn)
        ttk.Label(outer, text="Journal (aucun mot de passe ne doit apparaître) :").pack(anchor="w")
        self.log_widget = tk.Text(outer, height=10, wrap="word", state="disabled")
        self.log_widget.pack(fill="x")
        self._log("Le bouton Flash ne lance jamais erase_flash.")

    def _ports(self) -> None:
        try:
            from serial.tools import list_ports
            choices = [p.device for p in list_ports.comports()]
            if len(choices) == 1:
                self.port.set(choices[0])
            messagebox.showinfo("Ports USB", "\n".join(choices) or "Aucun port détecté.")
        except Exception as exc:
            messagebox.showerror("Ports", f"Erreur de détection : {exc}")

    def _build_jk(self, root: ttk.Frame) -> None:
        self._label(root, "Firmware .bin officiel :", 0)
        self._entry(root, self.jk_image, 0)
        self._picker(root, 0, self.jk_image, "Choisir le firmware JK")
        ttk.Radiobutton(
            root, text="Mise à jour — APP-ONLY à 0x10000 (NVS conservée)",
            variable=self.jk_mode, value="update"
        ).grid(row=1, column=0, columnspan=3, sticky="w", padx=8, pady=8)
        ttk.Radiobutton(
            root, text="Nouvel ESP32 uniquement — image FULL à 0x0000",
            variable=self.jk_mode, value="factory"
        ).grid(row=2, column=0, columnspan=3, sticky="w", padx=8)
        ttk.Label(
            root, text="Ne jamais choisir FULL pour mettre à jour un ESP32 déjà associé.\n"
                       "Vérifie que le fichier correspond à la carte et au mode sélectionnés."
        ).grid(row=3, column=0, columnspan=3, sticky="w", padx=8, pady=12)
        ttk.Button(root, text="Flasher le lecteur JK", command=self._flash_jk).grid(
            row=4, column=0, columnspan=3, pady=14
        )

    def _field(self, frame: ttk.Frame, text: str, key: str, row: int,
               password: bool = False) -> None:
        variable = tk.StringVar()
        self.fields[key] = variable
        self._label(frame, text, row)
        self._entry(frame, variable, row, password=password)

    def _build_vpn(self, root: ttk.Frame) -> None:
        self._label(root, "Firmware VPN précompilé :", 0)
        self._entry(root, self.vpn_image, 0)
        self._picker(root, 0, self.vpn_image, "Choisir un .bin (option expert)")
        ttk.Label(root,text="Intégré à l'EXE recommandé ; champ facultatif.",
                  foreground="#347d5a").grid(row=0,column=3,padx=5)
        ttk.Radiobutton(root,
            text="Nouvelle passerelle uniquement — image FACTORY à 0x0",
            variable=self.vpn_mode,value="factory").grid(
                row=1,column=0,columnspan=3,sticky="w",padx=8)
        ttk.Radiobutton(root,
            text="Mise à jour passerelle déjà configurée — APP-ONLY à 0x10000",
            variable=self.vpn_mode,value="update").grid(
                row=1,column=2,sticky="w",padx=8)
        rows = [
            ("Wi-Fi 2,4 GHz SSID :", "wifi_ssid", False),
            ("Mot de passe Wi-Fi :", "wifi_password", True),
            ("Clé Tailscale personnelle :", "tailscale_key", True),
            ("Token privé de passerelle :", "gateway_token", True),
            ("ESP JK 1 — IP locale :", "jk1_ip", False),
            ("ESP JK 1 — code :", "jk1_code", True),
            ("ESP JK 2 — IP locale (option) :", "jk2_ip", False),
            ("ESP JK 2 — code (option) :", "jk2_code", True),
            ("ESP JK 3 — IP locale (option) :", "jk3_ip", False),
            ("ESP JK 3 — code (option) :", "jk3_code", True),
        ]
        for row, (label, key, password) in enumerate(rows, start=2):
            self._field(root, label, key, row, password)
        ttk.Label(
            root, text="Aucun ESP-IDF, Git ou Python à installer : firmware VPN précompilé.\n"
                       "Secrets transmis uniquement par USB et enregistrés localement.\n"
                       "Nouvel ESP32 : FACTORY. Ancien ESP32 : APP-ONLY uniquement.",
            wraplength=720
        ).grid(row=12, column=0, columnspan=3, sticky="w", pady=8)
        ttk.Button(root, text="Flasher et configurer l'ESP32 VPN",
                   command=self._flash_vpn).grid(row=13,column=0,columnspan=3,pady=10)
        ttk.Button(root, text="Configurer via USB sans reflasher",
                   command=self._configure_only).grid(row=14,column=0,columnspan=3,pady=8)

    def _assert_ready(self) -> str:
        if self.busy:
            raise ValueError("Une opération est déjà en cours.")
        port = self.port.get().strip().upper()
        if not valid_serial_port(port):
            raise ValueError("Sélectionne un port USB COM valide.")
        return port

    def _flash_jk(self) -> None:
        try:
            port = self._assert_ready()
            path = Path(self.jk_image.get())
            if not path.is_file() or path.suffix.lower() != ".bin":
                raise ValueError("Choisis un fichier .bin JK existant.")
            if path.stat().st_size < 16384 or path.stat().st_size > 4_194_304:
                raise ValueError("Taille du firmware non plausible pour cet ESP32.")
            payload = path.read_bytes()
            factory = self.jk_mode.get() == "factory"
            if factory:
                if len(payload) < 0x10001 or payload[0x1000] != 0xE9 or payload[0x10000] != 0xE9:
                    raise ValueError("L'image FULL n'a pas les en-têtes attendus.")
                if not messagebox.askyesno(
                    "Image complète : attention", "UNIQUEMENT pour un nouvel ESP32 vierge.\n"
                    "Un mauvais choix peut remplacer le partitionnement et rendre "
                    "les anciennes données inaccessibles.\nConfirmer le flash à 0x0000 ?"
                ):
                    return
            elif payload[0] != 0xE9:
                raise ValueError("Le firmware APP-ONLY n'a pas l'en-tête ESP32 attendu.")
            offset = "0x0" if factory else "0x10000"
            self._run_async(lambda: self._do_jk(port, path, offset))
        except Exception as exc:
            messagebox.showerror("Flash JK", str(exc))

    def _do_jk(self, port: str, file: Path, offset: str) -> None:
        self._log(f"Flash JK {port}, offset {offset}, fichier {file.name}")
        # Bundled esptool is used directly, without erase_flash or shell.
        from esptool import main as esptool_main
        esptool_main(["--chip", "esp32", "--port", port, "--baud", "460800",
                      "write_flash", "--flash_mode", "dio", "--flash_freq", "40m",
                      "--flash_size", "4MB", offset, str(file)])
        self._log("Flash JK terminé. Redémarre le lecteur, puis vérifie la version dans l'APK.")

    @staticmethod
    def _bundled_vpn_image(mode: str) -> Path:
        base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
        names = {
            "factory": "smart-energy-vpn-USB-FACTORY-ESP32-0x0.bin",
            "update": "smart-energy-vpn-USB-APP-ESP32-0x10000.bin",
        }
        if mode not in names:
            raise ValueError("Mode VPN invalide.")
        file = base / "embedded_firmware" / names[mode]
        if not file.is_file():
            raise ValueError("Ce flasheur ne contient pas les images VPN. "
                             "Télécharge le kit Windows avec firmware intégré "
                             "ou sélectionne explicitement le .bin officiel.")
        return file

    def _vpn_values(self) -> dict[str, str]:
        values = {
            key: field.get().strip() if key not in (
                "wifi_password", "jk1_code", "jk2_code", "jk3_code",
                "gateway_token", "tailscale_key"
            ) else field.get()
            for key, field in self.fields.items()
        }
        validate_vpn_config(values)
        self.secret_values = [
            v for k, v in values.items() if k.endswith("_code")
            or k in ("wifi_password", "tailscale_key", "gateway_token")
        ]
        return values

    def _flash_vpn(self) -> None:
        try:
            from vpn_usb import check_precompiled_image
            port = self._assert_ready()
            values = self._vpn_values()
            mode = self.vpn_mode.get()
            if self.vpn_image.get().strip():
                file = Path(self.vpn_image.get().strip())
            else:
                file = self._bundled_vpn_image(mode)
            check_precompiled_image(file, mode)
            if not messagebox.askyesno(
                "Confirmer le flash ESP32 VPN",
                "Vérifie qu'il s'agit de la carte ESP32 VPN, pas d'un lecteur JK.\n"
                + ("FACTORY remplace le partitionnement et risque de rendre les "
                   "anciennes données inaccessibles.\n" if mode == "factory" else
                   "APP-ONLY conserve le partitionnement/NVS existants.\n")
                + "Après flash, le programme enverra les champs du formulaire "
                  "par le câble USB uniquement. Continuer ?"
            ):
                return
            self._run_async(lambda: self._do_vpn(port, file, mode, values))
        except Exception as exc:
            messagebox.showerror("Flash VPN", str(exc))

    def _do_vpn(self, port: str, image: Path, mode: str,
                values: dict[str, str]) -> None:
        from vpn_usb import flash_precompiled, provision_over_serial
        flash_precompiled(port, image, mode, self._log)
        provision_over_serial(port, values, self._log)

    def _configure_only(self) -> None:
        try:
            port = self._assert_ready()
            values = self._vpn_values()
            if not messagebox.askyesno(
                "Configuration physique",
                "Pour une passerelle déjà configurée : redémarre-la, "
                "puis maintiens BOOT pendant 3 secondes dans les 9 secondes "
                "suivant son démarrage (pas pendant la mise sous tension).\n\n"
                "Cette action ne flashe aucun firmware et n'efface pas les BMS. "
                "Continuer ?"
            ):
                return
            from vpn_usb import provision_over_serial
            self._run_async(lambda: provision_over_serial(port, values, self._log))
        except Exception as exc:
            messagebox.showerror("Configuration VPN", str(exc))

    def _run_async(self, callback: Callable[[], None]) -> None:
        self.busy = True
        def job() -> None:
            try:
                callback()
                self.events.put(("done", "Opération terminée."))
            except BaseException as exc:
                self.events.put(("error", str(exc)))
        threading.Thread(target=job, daemon=True).start()

    def _log(self, message: str) -> None:
        safe = message
        for secret in self.secret_values:
            if secret and len(secret) >= 4:
                safe = safe.replace(secret, "[MASQUÉ]")
        self.events.put(("log", safe))

    def _drain(self) -> None:
        try:
            while True:
                kind, message = self.events.get_nowait()
                if kind == "log":
                    self.log_widget.configure(state="normal")
                    self.log_widget.insert("end", message + "\n")
                    self.log_widget.see("end")
                    self.log_widget.configure(state="disabled")
                else:
                    self.busy = False
                    if kind == "done":
                        messagebox.showinfo("Smart Energy", message)
                    else:
                        messagebox.showerror("Smart Energy", message)
        except queue.Empty:
            pass
        self.after(100, self._drain)


if __name__ == "__main__":
    Flasher().mainloop()
