#!/usr/bin/env python3
"""Smart Energy — Windows ESP32 and ESP32-S3 flasher (development utility).

No automatic erase-flash. JK firmware can be installed APP-ONLY to 0x10000.
JK and VPN firmware are built in GitHub Actions and bundled in one EXE.
No personal tokens are uploaded to GitHub or stored in the executable.
"""
from __future__ import annotations

import ipaddress
from contextlib import contextmanager
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


def ssid_looks_five_ghz_only(ssid: str) -> bool:
    """SSID text is a hint, not proof of radio band. Warn, don't reject."""
    name = ssid.strip().casefold()
    return name == "5ghz" or name.endswith(("_5ghz", "-5ghz", " 5ghz"))


def validate_vpn_config(values: dict[str, str]) -> None:
    if not values["wifi_ssid"] or len(values["wifi_ssid"].encode("utf-8")) > 32:
        raise ValueError("SSID Wi-Fi invalide : 1 à 32 octets.")
    if not 8 <= len(values["wifi_password"]) <= 63:
        raise ValueError("Mot de passe Wi-Fi invalide : 8 à 63 caractères.")
    if not values["tailscale_key"].startswith("tskey-auth-"):
        raise ValueError("Indique une clé d'association Tailscale tskey-auth- personnelle.")
    if not 20 <= len(values["gateway_token"]) <= 128:
        raise ValueError("Token VPN : utilise 20 à 128 caractères.")
    count=0
    for index in range(1, 4):
        host = values[f"jk{index}_ip"].strip()
        code = values[f"jk{index}_code"]
        if bool(host) != bool(code):
            raise ValueError(f"ESP JK {index} : renseigne à la fois l'IP et le code.")
        if host and not valid_lan_ip(host):
            raise ValueError(f"ESP JK {index} : l'adresse doit être une IPv4 privée du LAN.")
        if code and not 8 <= len(code) <= 32:
            raise ValueError(f"ESP JK {index} : code personnel invalide (8–32).")
        if host:count+=1
    for index in range(1,4):
        host=values[f"volt{index}_ip"].strip()
        code=values[f"volt{index}_code"]
        if bool(host)!=bool(code):
            raise ValueError(f"Voltronic INV{index} : IP et code obligatoires ensemble.")
        if host and not valid_lan_ip(host):
            raise ValueError(f"Voltronic INV{index} : IPv4 privée du LAN obligatoire.")
        if code and not 8<=len(code)<=32:
            raise ValueError(f"Voltronic INV{index} : code ESP32-S3 invalide (8–32).")
        if host:count+=1
    if count<1:raise ValueError("Configure au moins un ESP JK ou Voltronic.")
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
        "SE_VOLT_IP": values["volt1_ip"],
        "SE_VOLT_READ_PATH": "/api/v1/telemetry" if values["volt1_ip"] else "",
        "SE_VOLT_READ_CODE": values["volt1_code"],
    }
    return "# PRIVATE — NEVER COMMIT OR SEND THIS FILE\n" + "".join(
        f"CONFIG_{key}={json.dumps(value, ensure_ascii=False)}\n"
        for key, value in settings.items()
    )


def valid_serial_port(port: str) -> bool:
    return bool(re.fullmatch(r"COM[1-9][0-9]*", port.strip().upper()))


def selected_serial_port(selection: str) -> str:
    """Safely recover a COM port from a dropdown label, never a shell command."""
    match = re.fullmatch(
        r"(COM[1-9][0-9]*)(?:\s+—\s+[^\r\n]+)?",
        selection.strip(), flags=re.IGNORECASE
    )
    if not match:
        raise ValueError("Choisis un port COM dans la liste déroulante.")
    return match.group(1).upper()


def serial_port_label(device: str, description: str) -> str:
    """Show a short, human-readable device name without a serial number."""
    if not valid_serial_port(device):
        raise ValueError("Port COM invalide.")
    short_description = " ".join(description.split())[:66]
    if not short_description or short_description.lower() in (
        "n/a", "unknown", "périphérique inconnu"
    ):
        return device.upper()
    return f"{device.upper()} — {short_description}"


def ensure_esptool_console_streams() -> None:
    """PyInstaller --windowed sets stdout/stderr to None; esptool flushes them."""
    if sys.stdout is None:
        sys.stdout = open(os.devnull, "w", encoding="utf-8")
    if sys.stderr is None:
        sys.stderr = open(os.devnull, "w", encoding="utf-8")


class FlashProgressWriter:
    """Observe real esptool writing percentages, not simulated progress."""

    PERCENT = re.compile(r"(?<!\d)(100|[1-9]?\d)\s*%")

    def __init__(self, target, callback: Callable[[int], None]) -> None:
        self.target = target
        self.callback = callback
        self.pending = ""
        self.last = -1
        self.encoding = getattr(target, "encoding", "utf-8") or "utf-8"

    def write(self, content: str) -> int:
        if not isinstance(content, str):
            content = str(content)
        self.target.write(content)
        # Support esptool carriage-return output and chunked writes.
        self.pending = (self.pending + content)[-512:]
        for piece in re.split(r"[\r\n]", self.pending):
            if "writing" not in piece.lower():
                continue
            match = self.PERCENT.search(piece)
            if match:
                percent = int(match.group(1))
                if percent > self.last:
                    self.last = percent
                    self.callback(percent)
        if "\r" in self.pending or "\n" in self.pending:
            self.pending = re.split(r"[\r\n]", self.pending)[-1][-256:]
        return len(content)

    def flush(self) -> None:
        self.target.flush()

    def isatty(self) -> bool:
        return False

    def __getattr__(self, name: str):
        # Allow esptool to inspect standard stream capabilities as before.
        return getattr(self.target, name)


@contextmanager
def monitor_esptool_progress(callback: Callable[[int], None]):
    """Always restore stdout after the flash, even if it fails."""
    ensure_esptool_console_streams()
    previous = sys.stdout
    sys.stdout = FlashProgressWriter(previous, callback)
    try:
        yield
    finally:
        sys.stdout = previous


class Flasher(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("Smart Energy — JK / VPN / Voltronic ESP32-S3")
        self.geometry("840x810")
        self.minsize(740, 620)
        self.events: queue.Queue[tuple[str, str]] = queue.Queue()
        self.busy = False
        self.secret_values: list[str] = []
        self.operation_mode = ""
        self.progress_percent = 0
        self.port = tk.StringVar()
        self.jk_image = tk.StringVar()
        self.jk_mode = tk.StringVar(value="update")
        self.vpn_image = tk.StringVar()
        self.vpn_mode = tk.StringVar(value="update")
        self.vpn_chip = tk.StringVar(value="esp32")  # Explicit chip; never guess from COM port.
        self.volt_mode = tk.StringVar(value="update")
        self.volt_image = tk.StringVar()
        self.volt_fields: dict[str, tk.StringVar] = {}
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
            outer, text="JK classique · VPN classique/S3 · Voltronic S3 (lecture seule)",
            font=("Segoe UI", 12, "bold")
        ).pack(anchor="w", pady=(0, 8))
        connection = ttk.Frame(outer)
        connection.pack(fill="x")
        connection.columnconfigure(1, weight=1)
        self._label(connection, "Port série USB :", 0)
        self.port_combo = ttk.Combobox(
            connection, textvariable=self.port, state="readonly",
            width=48, postcommand=self._ports
        )
        self.port_combo.grid(row=0, column=1, sticky="ew", padx=8, pady=5)
        ttk.Button(connection, text="↻ Actualiser", command=self._ports).grid(
            row=0, column=2, padx=6
        )
        self.port_hint = ttk.Label(connection,
            text="Branche un seul ESP32 à la fois, puis choisis son port USB.")
        self.port_hint.grid(row=1, column=0, columnspan=3,
                            sticky="w", padx=8, pady=(0, 4))
        progress = ttk.LabelFrame(outer, text="Progression du flash / de la configuration", padding=8)
        progress.pack(fill="x", pady=(4, 8))
        self.progress_label = ttk.Label(progress, text="Prêt — sélectionne ton ESP32.")
        self.progress_label.pack(anchor="w", pady=(0, 5))
        self.progress_bar = ttk.Progressbar(progress, orient="horizontal",
                                            mode="determinate", maximum=100, value=0)
        self.progress_bar.pack(fill="x")
        tabs = ttk.Notebook(outer)
        tabs.pack(fill="both", expand=True, pady=10)
        jk = ttk.Frame(tabs, padding=12)
        vpn = ttk.Frame(tabs, padding=12)
        voltronic = ttk.Frame(tabs, padding=12)
        jk.columnconfigure(1, weight=1)
        vpn.columnconfigure(1, weight=1)
        voltronic.columnconfigure(1, weight=1)
        tabs.add(jk, text="ESP32 JK")
        tabs.add(vpn, text="ESP32 VPN")
        tabs.add(voltronic, text="Voltronic S3")
        self._build_jk(jk)
        self._build_vpn(vpn)
        self._build_voltronic(voltronic)
        ttk.Label(outer, text="Journal (aucun mot de passe ne doit apparaître) :").pack(anchor="w")
        self.log_widget = tk.Text(outer, height=10, wrap="word", state="disabled")
        self.log_widget.pack(fill="x")
        self._log("Le bouton Flash ne lance jamais erase_flash.")
        self._ports()

    def _ports(self) -> None:
        """Refresh dropdown on launch, button click and just before opening it."""
        try:
            from serial.tools import list_ports
            devices = sorted(
                (p for p in list_ports.comports() if valid_serial_port(p.device)),
                key=lambda p: int(p.device.upper()[3:])
            )
            choices = [serial_port_label(p.device, p.description or "") for p in devices]
            previous = self.port.get()
            previous_com = ""
            if previous:
                try:
                    previous_com = selected_serial_port(previous)
                except ValueError:
                    pass
            self.port_combo.configure(values=choices)
            retained = next(
                (label for label in choices
                 if selected_serial_port(label) == previous_com), ""
            )
            if retained:
                self.port.set(retained)
            elif not previous and len(choices) == 1:
                # Only one board: convenient initial selection.
                self.port.set(choices[0])
            else:
                # An unplugged port must never silently switch to another board.
                self.port.set("")
            if not choices:
                self.port_hint.configure(
                    text="Aucun port COM : branche l'ESP32 avec un câble USB données, "
                         "puis clique Actualiser."
                )
            elif len(choices) == 1:
                self.port_hint.configure(text="1 port COM détecté. Vérifie qu'il s'agit "
                    "du bon ESP32 avant de flasher.")
            else:
                self.port_hint.configure(text=f"{len(choices)} ports COM détectés. "
                    "Sélectionne celui du bon ESP32 ; aucun choix automatique.")
        except Exception as exc:
            self.port_combo.configure(values=())
            self.port.set("")
            self.port_hint.configure(text="Impossible de lire les ports COM.")
            messagebox.showerror("Ports", f"Erreur de détection : {exc}")

    def _build_jk(self, root: ttk.Frame) -> None:
        ttk.Label(
            root,
            text="Firmware JK 0.3.14-dev corrigé (identifiant unique par ESP32), précompilé et inclus dans cet EXE. "
                 "Aucun fichier à chercher.",
            wraplength=720
        ).grid(row=0, column=0, columnspan=3, sticky="w", padx=8, pady=(10, 15))
        modes = ttk.LabelFrame(root, text="Type de flash", padding=8)
        modes.grid(row=1, column=0, columnspan=3, sticky="ew", padx=8)
        ttk.Radiobutton(
            modes, text="Mise à jour — APP-ONLY à 0x10000 (conserve les données NVS)",
            variable=self.jk_mode, value="update"
        ).pack(anchor="w", pady=5)
        ttk.Radiobutton(
            modes, text="Nouvel ESP32 vierge — FACTORY à 0x0000",
            variable=self.jk_mode, value="factory"
        ).pack(anchor="w", pady=5)
        ttk.Label(
            root,
            text="ATTENTION : FACTORY uniquement pour un nouvel ESP32. "
                 "Ne jamais utiliser FACTORY pour mettre à jour un JK déjà associé.",
            wraplength=720
        ).grid(row=2, column=0, columnspan=3, sticky="w", padx=8, pady=12)
        expert = ttk.Frame(root)
        expert.columnconfigure(1, weight=1)
        expert.grid(row=4, column=0, columnspan=3, sticky="ew", padx=8)
        expert.grid_remove()
        self._label(expert, "Firmware JK externe (.bin) :", 0)
        self._entry(expert, self.jk_image, 0, width=42)
        self._picker(expert, 0, self.jk_image, "Choisir un firmware JK officiel")
        ttk.Button(
            root, text="Options avancées : utiliser un autre firmware",
            command=lambda: expert.grid_remove()
                if expert.winfo_ismapped() else expert.grid()
        ).grid(row=3, column=0, columnspan=3, sticky="w", padx=8)
        ttk.Button(root, text="Flasher le lecteur JK",
                   command=self._flash_jk).grid(
            row=5, column=0, columnspan=3, pady=20
        )

    def _field(self, frame: ttk.Frame, text: str, key: str, row: int,
               password: bool = False) -> None:
        variable = tk.StringVar()
        self.fields[key] = variable
        self._label(frame, text, row)
        self._entry(frame, variable, row, password=password)

    def _build_vpn(self, root: ttk.Frame) -> None:
        # Each section owns its own 2-column grid. The old overlapping
        # radio button in column 2 consumed the available width and made all
        # Entry widgets effectively invisible on normal Windows DPI settings.
        ttk.Label(
            root,
            text="Firmware VPN précompilé inclus : remplis le formulaire, "
                 "aucun fichier .bin, Git ou ESP-IDF nécessaire.",
            wraplength=720
        ).grid(row=0, column=0, columnspan=3, sticky="w", padx=8, pady=5)
        board = ttk.LabelFrame(root, text="Matériel VPN à flasher", padding=5)
        board.grid(row=1, column=0, columnspan=3, sticky="ew", padx=8, pady=5)
        ttk.Radiobutton(board,
            text="ESP32 classique (4 Mo flash)",
            variable=self.vpn_chip, value="esp32").pack(anchor="w", pady=2)
        ttk.Radiobutton(board,
            text="ESP32-S3 N16R8 (16 Mo flash / 8 Mo PSRAM) — prototype",
            variable=self.vpn_chip, value="esp32s3").pack(anchor="w", pady=2)
        modes = ttk.LabelFrame(root, text="Type de flash VPN", padding=5)
        modes.grid(row=2, column=0, columnspan=3, sticky="ew", padx=8, pady=6)
        ttk.Radiobutton(
            modes, text="Nouvelle passerelle — FACTORY à 0x0000 (ESP32 vierge uniquement)",
            variable=self.vpn_mode, value="factory"
        ).pack(anchor="w", pady=2)
        ttk.Radiobutton(
            modes, text="Mise à jour — APP-ONLY à 0x10000 (conserve NVS)",
            variable=self.vpn_mode, value="update"
        ).pack(anchor="w", pady=2)

        connection = ttk.LabelFrame(root, text="Wi-Fi et Tailscale", padding=5)
        connection.grid(row=3, column=0, columnspan=3, sticky="ew",
                        padx=8, pady=5)
        connection.columnconfigure(1, weight=1)
        for i, (caption, key, password) in enumerate([
            ("Wi-Fi 2,4 GHz (SSID) :", "wifi_ssid", False),
            ("Mot de passe Wi-Fi :", "wifi_password", True),
            ("Clé Tailscale tskey-auth- :", "tailscale_key", True),
            ("Token de passerelle (20-128) :", "gateway_token", True),
        ]):
            self._field(connection, caption, key, i, password)

        readers = ttk.LabelFrame(root, text="Lecteurs JK du réseau local", padding=5)
        readers.grid(row=4, column=0, columnspan=3, sticky="ew",
                     padx=8, pady=5)
        readers.columnconfigure(1, weight=1)
        for i, (caption, key, password) in enumerate([
            ("ESP JK 1 — IP locale :", "jk1_ip", False),
            ("ESP JK 1 — code :", "jk1_code", True),
            ("ESP JK 2 — IP (facultatif) :", "jk2_ip", False),
            ("ESP JK 2 — code (facultatif) :", "jk2_code", True),
            ("ESP JK 3 — IP (facultatif) :", "jk3_ip", False),
            ("ESP JK 3 — code (facultatif) :", "jk3_code", True),
        ]):
            self._field(readers, caption, key, i, password)

        volts=ttk.LabelFrame(root,text="Voltronic ESP32-S3 — INV1 à INV3 (VPN lecture seule)",padding=5)
        volts.grid(row=5,column=0,columnspan=3,sticky="ew",padx=8,pady=5)
        volts.columnconfigure(1,weight=1)
        for i,(caption,key,password) in enumerate([
            ("INV1 — IP locale :", "volt1_ip",False),
            ("INV1 — code ESP :", "volt1_code",True),
            ("INV2 — IP locale (optionnel) :", "volt2_ip",False),
            ("INV2 — code (optionnel) :", "volt2_code",True),
            ("INV3 — IP locale (optionnel) :", "volt3_ip",False),
            ("INV3 — code (optionnel) :", "volt3_code",True),
        ]):
            self._field(volts,caption,key,i,password)
        expert = ttk.Frame(root)
        expert.columnconfigure(1, weight=1)
        expert.grid(row=6, column=0, columnspan=3, sticky="ew", padx=8)
        expert.grid_remove()
        self._label(expert, "Firmware VPN externe (.bin) :", 0)
        self._entry(expert, self.vpn_image, 0, width=42)
        self._picker(expert, 0, self.vpn_image, "Choisir un firmware VPN officiel")
        ttk.Button(
            root, text="Options avancées : sélectionner un .bin externe",
            command=lambda: expert.grid_remove()
                if expert.winfo_ismapped() else expert.grid()
        ).grid(row=5, column=0, columnspan=3, sticky="w",
               padx=8, pady=(4, 0))
        ttk.Label(root,
            text="VPN déjà fonctionnel : tester d’abord sur une carte de réserve. "
                 "Ne pas choisir FACTORY pour une mise à jour.",
            wraplength=720
        ).grid(row=7,column=0,columnspan=3,sticky="w",padx=8,pady=(4,0))
        ttk.Button(root, text="Flasher et configurer l'ESP32 VPN",
                   command=self._flash_vpn).grid(
            row=8, column=0, columnspan=3, pady=(8, 4))
        ttk.Button(root, text="Configurer via USB sans reflasher",
                   command=self._configure_only).grid(
            row=9, column=0, columnspan=3, pady=(0, 6))

    def _build_voltronic(self, root: ttk.Frame) -> None:
        ttk.Label(root,
            text="ESP32-S3 N16R8 uniquement · lecteur Voltronic RS232 2400 bauds. "
                 "Firmware Voltronic avec polling stable et OTA sous réserve de migration.",
            wraplength=690).grid(row=0,column=0,columnspan=3,sticky="w",padx=8,pady=8)
        modes=ttk.LabelFrame(root,text="Type de flash Voltronic S3",padding=8)
        modes.grid(row=1,column=0,columnspan=3,sticky="ew",padx=8,pady=8)
        ttk.Radiobutton(modes,text="Nouvel ESP32-S3 — FACTORY à 0x0",
                        variable=self.volt_mode,value="factory").pack(anchor="w")
        ttk.Radiobutton(modes,text="Mise à jour Voltronic S3 — APP-ONLY : conserver Wi-Fi et code",
                        variable=self.volt_mode,value="update").pack(anchor="w")
        ttk.Label(root,text="APP-ONLY : conserve Wi-Fi, code de lecture et NVS, "
                  "sans reconfiguration USB. FACTORY : S3 neuf uniquement.",
                  wraplength=690).grid(row=8,column=0,columnspan=3,sticky="w",padx=8,pady=4)
        params=ttk.LabelFrame(root,text="Configuration USB (première installation)",padding=8)
        params.grid(row=2,column=0,columnspan=3,sticky="ew",padx=8,pady=8)
        params.columnconfigure(1,weight=1)
        for i,(description,key,secret) in enumerate([
            ("Wi-Fi 2,4 GHz (SSID) :","wifi_ssid",False),
            ("Mot de passe Wi-Fi :","wifi_password",True),
            ("Code privé de lecture (8–32) :","read_code",True),
        ]):
            variable=tk.StringVar()
            self.volt_fields[key]=variable
            self._label(params,description,i)
            self._entry(params,variable,i,password=secret)
        expert=ttk.Frame(root)
        expert.columnconfigure(1,weight=1)
        expert.grid(row=4,column=0,columnspan=3,sticky="ew",padx=8)
        expert.grid_remove()
        self._label(expert,"Image Voltronic S3 externe (.bin) :",0)
        self._entry(expert,self.volt_image,0,width=42)
        self._picker(expert,0,self.volt_image,"Choisir un firmware Voltronic S3")
        ttk.Button(root,text="Options expertes : fichier .bin externe",
                   command=lambda: expert.grid_remove()
                       if expert.winfo_ismapped() else expert.grid()).grid(
            row=3,column=0,columnspan=3,sticky="w",padx=8,pady=5)
        ttk.Label(root,
            text="Attention : un port RS232 onduleur NE se branche jamais "
                 "directement sur un GPIO 3,3 V ! MAX3232 et câblage validé "
                 "indispensables. Ne pas écraser le VPN avec ce firmware.",
            wraplength=690).grid(row=5,column=0,columnspan=3,sticky="w",padx=8,pady=8)
        ttk.Button(root,text="Flasher et configurer Voltronic S3",
                   command=self._flash_voltronic).grid(
            row=6,column=0,columnspan=3,pady=6)
        ttk.Button(root,text="Configurer Voltronic via USB sans reflasher",
                   command=self._configure_voltronic_only).grid(
            row=7,column=0,columnspan=3,pady=6)
        ttk.Button(root,text="EXPERT · Migration USB vers OTA (S3 de test uniquement)",
                   command=self._migrate_voltronic_to_ota).grid(
            row=9,column=0,columnspan=3,pady=8)
        ttk.Label(root,text="Attention : cette opération modifie le bootloader et "
                  "la table des partitions. Uniquement pour un S3 de réserve ; "
                  "validation sur la carte de production en attente.",
                  wraplength=690).grid(row=10,column=0,columnspan=3,pady=5)

    @staticmethod
    def _bundled_voltronic_image(mode: str) -> Path:
        base=Path(getattr(sys,"_MEIPASS",Path(__file__).resolve().parent))
        names={
            "factory":"smart-energy-voltronic-S3-FACTORY-0x0.bin",
            "update":"smart-energy-voltronic-S3-APP-0x10000.bin",
        }
        if mode not in names:
            raise ValueError("Mode flash Voltronic S3 invalide.")
        file=base/"embedded_firmware"/names[mode]
        if not file.is_file():
            raise ValueError("Firmware Voltronic S3 absent de ce flasheur : "
                "télécharge le nouveau EXE tout-en-un GitHub.")
        return file

    def _voltronic_values(self) -> dict[str,str]:
        from voltronic_usb import voltronic_config_line
        values={key:variable.get() for key,variable in self.volt_fields.items()}
        voltronic_config_line(values)  # Strict validation, no network.
        self.secret_values=[values.get("wifi_password",""),
                            values.get("read_code","")]
        return values

    def _flash_voltronic(self) -> None:
        try:
            from vpn_usb import check_precompiled_image
            port=self._assert_ready()
            mode=self.volt_mode.get()
            values=self._voltronic_values() if mode=="factory" else None
            image=(Path(self.volt_image.get().strip())
                   if self.volt_image.get().strip()
                   else self._bundled_voltronic_image(mode))
            check_precompiled_image(image,mode,chip="esp32s3")
            if not messagebox.askyesno("Flash Voltronic S3",
                "Vérifie la puce ESP32-S3 N16R8 et le port COM.\n"
                "FACTORY : CARTE VIERGE uniquement ; peut remplacer les partitions.\n"
                "APP-ONLY conserve Wi-Fi/code sans réassociation USB.\n"
                 "Jamais sur un ESP32 JK ou VPN déjà associé. Continuer ?"):
                return
            self._run_async(
                lambda:self._do_voltronic(port,image,mode,values),
                mode="voltronic")
        except Exception as exc:
            messagebox.showerror("Voltronic S3",str(exc))

    def _do_voltronic(self,port:str,image:Path,mode:str,
                      values:dict[str,str]|None)->None:
        from vpn_usb import flash_precompiled
        from voltronic_usb import provision_voltronic
        ensure_esptool_console_streams()
        self._progress_stage("Écriture du firmware Voltronic S3…")
        with monitor_esptool_progress(self._flash_percent):
            flash_precompiled(port,image,mode,self._log,chip="esp32s3")
        if mode=="update":
            self._progress_step(98,
                "APP-ONLY installé : NVS conservée, Wi-Fi et code inchangés.")
            return
        if values is None:
            raise ValueError("Configuration USB requise pour FACTORY.")
        self._progress_step(80,"Firmware S3 chargé, configuration USB…")
        provision_voltronic(port,values,self._log)
        self._progress_step(98,"Voltronic S3 : confirmation USB reçue.")

    @staticmethod
    def _bundled_voltronic_ota_folder()->Path:
        base=Path(getattr(sys,"_MEIPASS",Path(__file__).resolve().parent))
        from voltronic_ota_migration import validate_migration_images
        folder=base/"embedded_firmware"
        validate_migration_images(folder)
        return folder

    def _migrate_voltronic_to_ota(self)->None:
        try:
            from voltronic_ota_migration import flash_voltronic_ota_migration
            port=self._assert_ready()
            folder=self._bundled_voltronic_ota_folder()
            if not messagebox.askyesno("Migration OTA EXPÉRIMENTALE",
                "Cette opération est réservée à une carte ESP32-S3 de TEST !\n\n"
                "Elle remplace le bootloader et le partitionnement, mais n'écrit "
                "aucun octet sur la zone NVS. L'app doit être préparée pour OTA.\n"
                "Une erreur de flash peut nécessiter une récupération par USB.\n"
                "Confirmer la première étape ?"):
                return
            if not messagebox.askyesno("Dernière confirmation : carte S3 de TEST",
                f"Le programme écrira séparément trois images sur {port} :\n"
                "0x0000 bootloader, 0x8000 partitions, 0x10000 application.\n"
                "Aucun erase_flash. Ne pas utiliser sur la passerelle JK/VPN.\n"
                "Es-tu certain que le S3 est une carte de test ?"):
                return
            self._run_async(
                lambda:flash_voltronic_ota_migration(port,folder,self._log),
                mode="migration-ota")
        except Exception as ex:
            messagebox.showerror("Migration OTA",str(ex))

    def _configure_voltronic_only(self)->None:
        try:
            from voltronic_usb import provision_voltronic
            port=self._assert_ready()
            values=self._voltronic_values()
            if not messagebox.askyesno("Reconfiguration USB Voltronic S3",
                "Redémarre le S3 et maintiens BOOT 3 secondes "
                "dans les 9 premières secondes, sans flasher. Continuer ?"):
                return
            self._run_async(lambda:provision_voltronic(port,values,self._log),
                            mode="config")
        except Exception as exc:
            messagebox.showerror("Configuration Voltronic S3",str(exc))

    def _assert_ready(self) -> str:
        if self.busy:
            raise ValueError("Une opération est déjà en cours.")
        port = selected_serial_port(self.port.get())
        from serial.tools import list_ports
        available = {p.device.upper() for p in list_ports.comports()}
        if port not in available:
            self._ports()
            raise ValueError("Ce port COM n'est plus connecté. "
                             "Reconnecte l'ESP32 et actualise la liste.")
        return port

    def _flash_jk(self) -> None:
        try:
            port = self._assert_ready()
            # The signed-off images from our CI are included in the standard
            # EXE. A manually selected .bin is strictly an expert override.
            mode=self.jk_mode.get()
            path = (Path(self.jk_image.get().strip())
                    if self.jk_image.get().strip()
                    else self._bundled_jk_image(mode))
            if not path.is_file() or path.suffix.lower() != ".bin":
                raise ValueError("Firmware JK précompilé absent : "
                                 "utilise le flasheur officiel complet.")
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
            self._run_async(lambda: self._do_jk(port, path, offset), mode="jk")
        except Exception as exc:
            messagebox.showerror("Flash JK", str(exc))

    def _do_jk(self, port: str, file: Path, offset: str) -> None:
        ensure_esptool_console_streams()
        self._log(f"Flash JK {port}, offset {offset}, fichier {file.name}")
        self._progress_stage("Connexion à l'ESP32 JK et écriture du firmware…")
        from esptool import main as esptool_main
        with monitor_esptool_progress(self._flash_percent):
            esptool_main(["--chip", "esp32", "--port", port, "--baud", "460800",
                          "write_flash", "--flash_mode", "dio", "--flash_freq", "40m",
                          "--flash_size", "4MB", offset, str(file)])
        self._progress_step(98, "Image JK écrite et vérifiée.")
        self._log("Flash JK terminé. Redémarre le lecteur, puis vérifie la version dans l'APK.")

    @staticmethod
    def _bundled_jk_image(mode: str) -> Path:
        base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
        names = {
            "factory": "smart-jk-esp32-factory.bin",
            "update": "smart-jk-esp32-app-only.bin",
        }
        if mode not in names:
            raise ValueError("Mode JK invalide.")
        file = base / "embedded_firmware" / names[mode]
        if not file.is_file():
            raise ValueError(
                "Image JK intégrée introuvable. Télécharge l'EXE complet "
                "SmartEnergyFlasher (JK + VPN) depuis le dernier build."
            )
        return file

    @staticmethod
    def _bundled_vpn_image(mode: str, chip: str = "esp32") -> Path:
        base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent))
        names = {
            ("esp32","factory"): "smart-energy-vpn-USB-FACTORY-ESP32-0x0.bin",
            ("esp32","update"): "smart-energy-vpn-USB-APP-ESP32-0x10000.bin",
            ("esp32s3","factory"): "smart-energy-vpn-USB-FACTORY-ESP32S3-N16R8-0x0.bin",
            ("esp32s3","update"): "smart-energy-vpn-USB-APP-ESP32S3-N16R8-0x10000.bin",
        }
        if (chip,mode) not in names:
            raise ValueError("Type de puce VPN ou mode invalide.")
        file = base / "embedded_firmware" / names[(chip,mode)]
        if not file.is_file():
            raise ValueError("Ce flasheur ne contient pas les images VPN. "
                             "Télécharge le kit Windows avec firmware intégré "
                             "ou sélectionne explicitement le .bin officiel.")
        return file

    def _vpn_values(self) -> dict[str, str]:
        values = {
            key: field.get().strip() if key not in (
                "wifi_password", "jk1_code", "jk2_code", "jk3_code",
                "volt1_code", "volt2_code", "volt3_code",
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

    def _confirm_wifi_band(self, ssid: str) -> bool:
        if not ssid_looks_five_ghz_only(ssid):
            return True
        return messagebox.askyesno(
            "Vérifie le Wi-Fi 2,4 GHz",
            "Le nom du réseau sélectionné ressemble à un SSID 5 GHz. "
            "Un ESP32-S3 ne peut se connecter qu'au Wi-Fi 2,4 GHz.\\n\\n"
            "Choisis de préférence le réseau 2,4 GHz dans le formulaire. "
            "Si tu sais que ce SSID diffuse aussi en 2,4 GHz, "
            "veux-tu continuer quand même ?"
        )

    def _flash_vpn(self) -> None:
        try:
            from vpn_usb import check_precompiled_image
            port = self._assert_ready()
            mode = self.vpn_mode.get()
            chip = self.vpn_chip.get()
            if self.vpn_image.get().strip():
                file = Path(self.vpn_image.get().strip())
            else:
                file = self._bundled_vpn_image(mode, chip)
            check_precompiled_image(file, mode, chip=chip)

            # An existing VPN device already has a valid NVS identity and
            # configuration. Never overwrite those secrets just to update
            # its application firmware. Explicitly opt in to reprovisioning.
            keep_existing = mode == "update" and messagebox.askyesno(
                "Mise à jour VPN — conserver les réglages ?",
                "Conserver la configuration Wi-Fi, l'identité Tailscale et "
                "les codes JK déjà enregistrés ?\n\n"
                "Oui (recommandé) : APP-ONLY, sans réassociation USB, "
                "sans clé Tailscale à saisir.\n"
                "Non : mettre à jour ET reconfigurer avec les champs du formulaire."
            )
            values = None
            if not keep_existing:
                values = self._vpn_values()
                if not self._confirm_wifi_band(values["wifi_ssid"]):
                    return

            if not messagebox.askyesno(
                "Confirmer le flash ESP32 VPN",
                f"Matériel sélectionné : {chip}. Vérifie la bonne carte, pas un lecteur JK.\n"
                + ("FACTORY remplace le partitionnement et risque de rendre les "
                   "anciennes données inaccessibles.\n" if mode == "factory" else
                   "APP-ONLY conserve le partitionnement/NVS existants.\n")
                + ("Aucune donnée Wi-Fi/Tailscale/JK ne sera réécrite. "
                   "Seul le firmware sera mis à jour. Continuer ?"
                   if keep_existing else
                   "Après flash, les paramètres seront transmis par USB. Continuer ?")
            ):
                return
            self._run_async(lambda: self._do_vpn(port, file, mode, values, chip), mode="vpn")
        except Exception as exc:
            messagebox.showerror("Flash VPN", str(exc))

    def _do_vpn(self, port: str, image: Path, mode: str,
                values: dict[str, str] | None, chip: str = "esp32") -> None:
        ensure_esptool_console_streams()
        from vpn_usb import flash_precompiled, provision_over_serial
        self._progress_stage("Connexion à l'ESP32 VPN et écriture du firmware…")
        with monitor_esptool_progress(self._flash_percent):
            flash_precompiled(port, image, mode, self._log, chip=chip)
        if values is None:
            if mode != "update":
                raise ValueError("La configuration USB est obligatoire en mode FACTORY.")
            self._progress_step(98, "APP-ONLY installé : NVS conservée, aucune réassociation USB.")
            self._log("Mise à jour sans reconfiguration terminée. Redémarre et vérifie la liaison VPN.")
            return
        self._progress_step(80, "Firmware VPN écrit : attente USB (CH343/UART ou USB natif)…")
        provision_over_serial(
            port, values, self._log,
            on_ready=lambda: self._progress_step(
                92, "ESP32 prêt : configuration VPN en cours d'enregistrement…"))
        self._progress_step(98, "Configuration VPN enregistrée et confirmée par l'ESP32.")

    def _configure_only(self) -> None:
        try:
            port = self._assert_ready()
            values = self._vpn_values()
            if not self._confirm_wifi_band(values["wifi_ssid"]):
                return
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
            self._run_async(
                lambda: provision_over_serial(
                    port, values, self._log,
                    on_ready=lambda: self._progress_step(
                        90, "ESP32 prêt : transmission USB en cours…")),
                mode="config")
        except Exception as exc:
            messagebox.showerror("Configuration VPN", str(exc))

    def _progress_stage(self, message: str) -> None:
        self.events.put(("stage", message))

    def _progress_step(self, percent: int, message: str) -> None:
        self.events.put(("progress", str(max(0, min(99, percent)))))
        self.events.put(("stage", message))

    def _flash_percent(self, percent: int) -> None:
        # Esptool reports actual flash-writing percentage; VPN still needs
        # physical USB provisioning after image transfer.
        bar = 5 + round((70 if self.operation_mode in ("vpn","voltronic") else 90) *
                        percent / 100)
        self.events.put(("progress", str(bar)))
        self.events.put(("stage", f"Écriture du firmware : {percent} %"))

    def _run_async(self, callback: Callable[[], None], mode: str) -> None:
        self.busy = True
        self.operation_mode = mode
        self.progress_percent = 0
        self.progress_bar.configure(mode="indeterminate")
        self.progress_bar.start(14)
        self.progress_label.configure(text="Préparation de l'opération…")
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
                elif kind == "stage":
                    self.progress_label.configure(text=message)
                elif kind == "progress":
                    self.progress_bar.stop()
                    self.progress_bar.configure(mode="determinate")
                    self.progress_percent = max(self.progress_percent, int(message))
                    self.progress_bar.configure(value=self.progress_percent)
                else:
                    self.busy = False
                    self.progress_bar.stop()
                    self.progress_bar.configure(mode="determinate")
                    if kind == "done":
                        self.progress_bar.configure(value=100)
                        self.progress_label.configure(text="Terminé — 100 %")
                        messagebox.showinfo("Smart Energy", message)
                    else:
                        self.progress_bar.configure(value=self.progress_percent)
                        self.progress_label.configure(text="Échec — consulte le journal.")
                        messagebox.showerror("Smart Energy", message)
        except queue.Empty:
            pass
        self.after(100, self._drain)


if __name__ == "__main__":
    ensure_esptool_console_streams()
    Flasher().mainloop()
