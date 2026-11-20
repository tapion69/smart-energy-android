#!/usr/bin/env python3
"""Smart Energy — Windows ESP32 and ESP32-S3 flasher (development utility).

No automatic erase-flash. JK firmware can be installed APP-ONLY to 0x10000.
JK and VPN firmware are built in GitHub Actions and bundled in one EXE.
No personal tokens are uploaded to GitHub or stored in the executable.
"""
from __future__ import annotations

import ipaddress
from contextlib import contextmanager
from datetime import datetime
import io
import json
import os
from pathlib import Path
import queue
import re
import secrets
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
    vpn_name=values.get("gateway_name","")
    if vpn_name and not re.fullmatch(r"[a-z0-9](?:[a-z0-9-]{1,29}[a-z0-9])",vpn_name):
        raise ValueError("Nom VPN : 3 à 31 caractères minuscules, chiffres ou tirets (sans espace).")
    count=0
    seen={"jk":set(),"volt":set()}
    for index in range(1, 4):
        host = values.get(f"jk{index}_ip","").strip()
        name = values.get(f"jk{index}_name","").strip()
        code = values[f"jk{index}_code"]
        if bool(code)!=(bool(host) or bool(name)):
            raise ValueError(f"ESP JK {index} : indique le nom et le code ou l'IP et le code.")
        if host and not valid_lan_ip(host):
            raise ValueError(f"ESP JK {index} : IP invalide.")
        if name and (len(name.encode("utf-8"))>32 or any(ord(c)<32 or c=="|" for c in name)):
            raise ValueError(f"ESP JK {index} : nom invalide.")
        if name and name in seen["jk"]:
            raise ValueError("Deux ESP JK ont le même nom.")
        if name:seen["jk"].add(name)
        if code and not 8<=len(code)<=32:
            raise ValueError(f"ESP JK {index} : code personnel invalide (8–32).")
        if code:count+=1
    for index in range(1,4):
        host=values.get(f"volt{index}_ip","").strip()
        name=values.get(f"volt{index}_name","").strip()
        code=values[f"volt{index}_code"]
        # Legacy fixed-IP configurations stay valid for existing clients,
        # but the new VPN UI always submits blank IPs for name-based discovery.
        if host and not valid_lan_ip(host):
            raise ValueError(f"ESP Onduleur {index} : IP historique invalide.")
        if bool(code)!=(bool(name) or bool(host)):
            raise ValueError(f"ESP Onduleur {index} : nom/code ou ancienne IP/code obligatoires ensemble.")
        if name and (len(name.encode("utf-8"))>32 or any(ord(c)<32 or c=="|" for c in name)):
            raise ValueError(f"ESP Onduleur {index} : nom invalide.")
        if name and name in seen["volt"]:
            raise ValueError("Deux ESP onduleurs ont le même nom.")
        if name:seen["volt"].add(name)
        if code and not 8<=len(code)<=32:
            raise ValueError(f"ESP Onduleur {index} : code invalide (8–32).")
        if code:count+=1
    if count<1:raise ValueError("Configure au moins un ESP JK ou onduleur.")
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


class ScrollableTab(ttk.Frame):
    """Notebook page with a real vertical scrollbar and visible action bar.

    The body is a window embedded in a Canvas. Its width always follows the
    canvas, preventing form fields from overflowing beyond the right edge.
    """
    def __init__(self, parent: tk.Widget) -> None:
        super().__init__(parent)
        self.actions = ttk.Frame(self, padding=(8, 5))
        self.actions.pack(side="bottom", fill="x")
        ttk.Separator(self, orient="horizontal").pack(side="bottom", fill="x")
        pane = ttk.Frame(self)
        pane.pack(side="top", fill="both", expand=True)
        self.canvas = tk.Canvas(pane, highlightthickness=0, borderwidth=0)
        scrollbar = ttk.Scrollbar(pane, orient="vertical",
                                  command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.content = ttk.Frame(self.canvas, padding=12)
        self.content.columnconfigure(1, weight=1)
        self._window = self.canvas.create_window(
            (0, 0), window=self.content, anchor="nw")
        self.content.bind("<Configure>", self._content_changed)
        self.canvas.bind("<Configure>", self._canvas_changed)

    def _content_changed(self, event: tk.Event) -> None:
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def _canvas_changed(self, event: tk.Event) -> None:
        self.canvas.itemconfigure(self._window, width=event.width)
        self.canvas.configure(scrollregion=self.canvas.bbox("all"))

    def wheel(self, delta: int) -> None:
        if delta:
            self.canvas.yview_scroll(-delta, "units")


class Flasher(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("Smart Energy — Batteries JK / VPN / Onduleurs")
        self.geometry("960x780")
        self.minsize(740, 550)
        self.events: queue.Queue[tuple[str, str]] = queue.Queue()
        self.busy = False
        self.secret_values: list[str] = []
        self.operation_mode = ""
        self.progress_percent = 0
        self.port = tk.StringVar()
        self.jk_image = tk.StringVar() # Reserved for old unit test API; not user-visible
        self.jk_fields: dict[str, tk.StringVar] = {}
        self.jk_mode = tk.StringVar(value="update")
        self.vpn_image = tk.StringVar() # No custom firmware picker in UI
        self.vpn_mode = tk.StringVar(value="update")
        self.vpn_chip = tk.StringVar(value="esp32")  # Explicit chip; never guess from COM port.
        self.volt_mode = tk.StringVar(value="update")
        self.volt_image = tk.StringVar() # No custom firmware picker in UI
        self.inverter_type = tk.StringVar(value="voltronic")
        self.volt_fields: dict[str, tk.StringVar] = {}
        self.deye_fields: dict[str, tk.StringVar] = {}
        self.fields: dict[str, tk.StringVar] = {}
        self.gateway_token_entry: ttk.Entry | None = None
        self.gateway_token_visible = False
        self.gateway_token_toggle: ttk.Button | None = None
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
            outer, text="Batteries JK · VPN Tailscale · Onduleurs Voltronic et futurs Deye",
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
        tabs.pack(fill="both", expand=True, pady=(4, 6))
        self._scroll_tabs = [ScrollableTab(tabs) for _ in range(3)]
        jk, vpn, voltronic = self._scroll_tabs
        tabs.add(jk, text="ESP32 JK")
        tabs.add(vpn, text="ESP32 VPN")
        tabs.add(voltronic, text="Onduleurs")
        self._build_jk(jk.content)
        self._build_vpn(vpn.content)
        self._build_voltronic(voltronic.content)
        # Important actions remain visible even when the form is scrolled.
        ttk.Button(jk.actions, text="Mettre à jour ESP JK (USB si OTA impossible)",
                   command=self._flash_jk).pack(side="right", padx=5)
        ttk.Button(vpn.actions, text="Flasher VPN (mode sélectionné)",
                   command=self._flash_vpn).pack(side="right", padx=5)
        ttk.Button(vpn.actions,
                   text="Préparer OTA VPN (USB + sauvegarde NVS)",
                   command=self._migrate_vpn_to_ota).pack(side="left", padx=5)

        # On current VPN firmware: no USB permission switch. Android's
        # explicit confirmation precedes a whitelist-validated remote SET.
        ttk.Button(voltronic.actions, text="Flasher onduleur S3",
                   command=self._flash_voltronic).pack(side="right", padx=5)
        ttk.Button(voltronic.actions, text="Configurer S3 sans reflasher",
                   command=self._configure_voltronic_only).pack(side="left", padx=5)
        ttk.Button(voltronic.actions,
                   text="Préparer OTA Voltronic (USB + sauvegarde NVS)",
                   command=self._migrate_voltronic_to_ota).pack(side="left", padx=5)
        # Mouse wheel scrolls the tab body only when the pointer is over it.
        # Leave native scrolling elsewhere (logs, combobox popups) unchanged.
        self.bind_all("<MouseWheel>", self._scroll_active_tab, add="+")
        self.bind_all("<Button-4>", self._scroll_active_tab, add="+")
        self.bind_all("<Button-5>", self._scroll_active_tab, add="+")
        ttk.Label(outer, text="Journal (aucun mot de passe ne doit apparaître) :").pack(anchor="w")
        self.log_widget = tk.Text(outer, height=5, wrap="word", state="disabled")
        self.log_widget.pack(fill="x")
        self._log("Firmware précompilé uniquement ; aucune IP locale ni erase_flash.")
        self._ports()

    def _scroll_active_tab(self, event: tk.Event) -> str | None:
        """Windows wheel, plus X11 Button-4/5 for local GUI checks."""
        if not hasattr(self, "_scroll_tabs"):
            return None
        # Do not intercept wheels outside the notebook's visible canvas.
        for tab in self._scroll_tabs:
            if not tab.winfo_ismapped():
                continue
            canvas = tab.canvas
            x, y = event.x_root, event.y_root
            left, top = canvas.winfo_rootx(), canvas.winfo_rooty()
            if left <= x < left + canvas.winfo_width() and (
                top <= y < top + canvas.winfo_height()
            ):
                if getattr(event, "num", 0) == 4:
                    movement = 2
                elif getattr(event, "num", 0) == 5:
                    movement = -2
                else:
                    delta = getattr(event, "delta", 0)
                    movement = (1 if delta > 0 else -1) * max(
                        1, abs(int(delta / 120))) if delta else 0
                tab.wheel(movement)
                return "break"
        return None

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
            text="Lecteur JK : firmware précompilé intégré. "
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
            text="ESP JK déjà associé : utilise uniquement APP-ONLY. "
                 "Le flasheur vérifie la table de partitions et enregistre une "
                 "sauvegarde NVS privée avant toute écriture. "
                 "FACTORY reste réservé à une carte neuve.",
            wraplength=720
        ).grid(row=2, column=0, columnspan=3, sticky="w", padx=8, pady=12)
        ttk.Label(root,text="JK neuf : Wi-Fi, nom et code définis au flash USB. "
            "APP-ONLY préserve les lecteurs existants.",wraplength=700).grid(
            row=5,column=0,columnspan=3,pady=8)
        factory=ttk.LabelFrame(root,text="Première installation USB — ESP JK neuf",padding=8)
        factory.grid(row=6,column=0,columnspan=3,sticky="ew",padx=8,pady=5)
        factory.columnconfigure(1,weight=1)
        for i,(caption,key,secret) in enumerate([
            ("Nom personnalisé :","display_name",False),
            ("Wi-Fi 2,4 GHz (SSID) :","wifi_ssid",False),
            ("Mot de passe Wi-Fi :","wifi_password",True),
            ("Code de lecture (8–32) :","read_code",True),
        ]):
            variable=tk.StringVar(value="Batteries JK" if key=="display_name" else "")
            self.jk_fields[key]=variable
            self._label(factory,caption,i)
            self._entry(factory,variable,i,password=secret)

    def _field(self, frame: ttk.Frame, text: str, key: str, row: int,
               password: bool = False) -> None:
        variable = tk.StringVar()
        self.fields[key] = variable
        self._label(frame, text, row)
        field=self._entry(frame, variable, row, password=password)
        if key=="gateway_token":
            self.gateway_token_entry=field

    def _toggle_gateway_token_visibility(self) -> None:
        """Explicit opt-in visibility; token remains concealed by default."""
        if self.gateway_token_entry is None:
            return
        self.gateway_token_visible=not self.gateway_token_visible
        self.gateway_token_entry.configure(show="" if self.gateway_token_visible else "•")
        if self.gateway_token_toggle is not None:
            self.gateway_token_toggle.configure(
                text="Masquer le token" if self.gateway_token_visible else "Afficher le token")

    def _generate_gateway_token(self) -> None:
        """Create a fresh cryptographically secure credential locally, never log it."""
        self.fields["gateway_token"].set(secrets.token_urlsafe(32))

    def _copy_gateway_token(self) -> None:
        """User-visible clipboard action; only on explicit click."""
        token=self.fields["gateway_token"].get()
        if not 20<=len(token)<=128 or any(c.isspace() for c in token):
            messagebox.showerror("Token VPN","Le token doit contenir 20 à 128 caractères sans espace.")
            return
        self.clipboard_clear()
        self.clipboard_append(token)
        self.update_idletasks()
        messagebox.showinfo("Token VPN","Token copié dans le presse-papiers. Colle-le dans Smart Energy puis efface-le du presse-papiers après association. Ne le partage pas.")

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
            ("Nom du VPN dans Tailscale (3–31, sans espaces) :", "gateway_name", False),
        ]):
            self._field(connection, caption, key, i, password)

        # An independently generated token is the secure default for NEW VPN
        # installations. Existing gateways retain their current NVS token.
        self._generate_gateway_token()
        token_actions=ttk.Frame(connection)
        token_actions.grid(row=5,column=0,columnspan=3,sticky="w",padx=8,pady=5)
        ttk.Button(token_actions,text="Générer un nouveau token",
                   command=self._generate_gateway_token).pack(side="left",padx=(0,8))
        ttk.Button(token_actions,text="Copier le token",
                   command=self._copy_gateway_token).pack(side="left",padx=(0,8))
        self.gateway_token_toggle=ttk.Button(token_actions,text="Afficher le token",
                   command=self._toggle_gateway_token_visibility)
        self.gateway_token_toggle.pack(side="left")
        ttk.Label(connection,text="Tu peux aussi saisir ton propre token. Pour une passerelle déjà associée, conserve l'ancien : générer un autre token ne modifie PAS l'ESP.",
                  wraplength=670).grid(row=6,column=0,columnspan=3,sticky="w",padx=8,pady=(0,6))

        readers = ttk.LabelFrame(root, text="Lecteurs JK — noms et codes", padding=5)
        readers.grid(row=4, column=0, columnspan=3, sticky="ew",
                     padx=8, pady=5)
        readers.columnconfigure(1, weight=1)
        for i, (caption, key, password) in enumerate([
            ("ESP JK 1 — nom personnalisé :", "jk1_name", False),
            ("ESP JK 1 — code :", "jk1_code", True),
            ("ESP JK 2 — nom (facultatif) :", "jk2_name", False),
            ("ESP JK 2 — code (facultatif) :", "jk2_code", True),
            ("ESP JK 3 — nom (facultatif) :", "jk3_name", False),
            ("ESP JK 3 — code (facultatif) :", "jk3_code", True),
        ]):
            self._field(readers, caption, key, i, password)

        volts=ttk.LabelFrame(root,text="ESP32 Onduleurs — noms et codes (VPN)",padding=5)
        volts.grid(row=5,column=0,columnspan=3,sticky="ew",padx=8,pady=5)
        volts.columnconfigure(1,weight=1)
        for i,(caption,key,password) in enumerate([
            ("ESP Onduleur 1 — nom personnalisé :", "volt1_name",False),
            ("ESP Onduleur 1 — code ESP :", "volt1_code",True),
            ("ESP Onduleur 2 — nom (facultatif) :", "volt2_name",False),
            ("ESP Onduleur 2 — code (facultatif) :", "volt2_code",True),
            ("ESP Onduleur 3 — nom (facultatif) :", "volt3_name",False),
            ("ESP Onduleur 3 — code (facultatif) :", "volt3_code",True),
        ]):
            self._field(volts,caption,key,i,password)
        ttk.Label(volts,text="La passerelle VPN associe les ESP32 Onduleur par nom "+
            "et code uniquement. IP, port TCP et adresse esclave de la clé Deye "+
            "seront configurés dans l'APK sur l'ESP32 Onduleur (firmware Deye à développer).",
            wraplength=690).grid(row=8,column=0,columnspan=3,pady=5)
        ttk.Label(root,text="Les noms des ESP et leurs codes doivent correspondre "
            "exactement à ceux choisis au premier flash. Aucune adresse IP "
            "n'est nécessaire.",wraplength=700).grid(
              row=6,column=0,columnspan=3,sticky="w",padx=8,pady=8)
        ttk.Button(root,text="Configurer VPN par USB sans reflasher",
            command=self._configure_only).grid(row=7,column=0,columnspan=3,pady=8)
    def _build_voltronic(self, root: ttk.Frame) -> None:
        ttk.Label(root,
            text="Onglet Onduleurs. Voltronic/Axpert est disponible ; Deye sera ajouté après validation du firmware. "
                 "Voltronic conserve son câblage RS232 MAX3232.",
            wraplength=690).grid(row=0,column=0,columnspan=3,sticky="w",padx=8,pady=8)
        selector=ttk.LabelFrame(root,text="Famille d'onduleur",padding=8)
        selector.grid(row=1,column=0,columnspan=3,sticky="ew",padx=8)
        ttk.Radiobutton(selector,text="Voltronic / Axpert (disponible)",
              variable=self.inverter_type,value="voltronic",command=self._show_inverter_type).pack(anchor="w")
        ttk.Radiobutton(selector,text="Deye (Solarman V5 · lecture seule)",
              variable=self.inverter_type,value="deye",command=self._show_inverter_type).pack(anchor="w")
        modes=ttk.LabelFrame(root,text="Type de flash onduleur S3",padding=8)
        modes.grid(row=2,column=0,columnspan=3,sticky="ew",padx=8,pady=8)
        ttk.Radiobutton(modes,text="Nouvel ESP32-S3 — FACTORY à 0x0",
                        variable=self.volt_mode,value="factory").pack(anchor="w")
        ttk.Radiobutton(modes,text="Mise à jour S3 — APP-ONLY : conserver Wi-Fi et code",
                        variable=self.volt_mode,value="update").pack(anchor="w")
        ttk.Label(root,text="Voltronic précompilé : 0.3.8-dev-wifi-discovery. "
                  "mDNS et annonces UDP réparables, reconnexion Wi-Fi progressive ; "
                  "RS232 et MQTT/HA inchangés. APP-ONLY préserve la NVS et le code, "
                  "mais nécessite une implantation de partitions compatible. "
                  "FACTORY uniquement pour un S3 vierge.",
                  wraplength=690).grid(row=6,column=0,columnspan=3,sticky="w",padx=8,pady=4)
        params=ttk.LabelFrame(root,text="Configuration USB (première installation)",padding=8)
        params.grid(row=3,column=0,columnspan=3,sticky="ew",padx=8,pady=8)
        params.columnconfigure(1,weight=1)
        for i,(description,key,secret) in enumerate([
            ("Wi-Fi 2,4 GHz (SSID) :","wifi_ssid",False),
            ("Mot de passe Wi-Fi :","wifi_password",True),
            ("Code privé de lecture (8–32) :","read_code",True),
            ("Nom personnalisé (1–32 octets) :","display_name",False),
        ]):
            variable=tk.StringVar(value="Onduleur Voltronic" if key=="display_name" else "")
            self.volt_fields[key]=variable
            self._label(params,description,i)
            self._entry(params,variable,i,password=secret)
        self.deye_frame=ttk.LabelFrame(root,
            text="Clé Wi-Fi Deye — paramètres Solarman V5",padding=8)
        self.deye_frame.grid(row=4,column=0,columnspan=3,sticky="ew",padx=8,pady=8)
        self.deye_frame.columnconfigure(1,weight=1)
        for i,(label,key,default) in enumerate([
            ("IP privée de la clé Wi-Fi Deye :","dongle_ip",""),
            ("Port TCP de la clé :","dongle_port","8899"),
            ("Adresse esclave Modbus ID :","modbus_id","1"),
            ("Numéro de série du dongle :","dongle_serial",""),
            ("Numéro de série de l'onduleur :","inverter_serial",""),
        ]):
            variable=tk.StringVar(value=default)
            self.deye_fields[key]=variable
            self._label(self.deye_frame,label,i)
            self._entry(self.deye_frame,variable,i)
        ttk.Label(self.deye_frame,
            text="Première installation : paramètres enregistrés sur l'ESP32. "
                 "FC03 lecture seulement tant que les écritures n'ont pas été "
                 "validées sur le matériel.",
            wraplength=690).grid(row=5,column=0,columnspan=3,padx=8,pady=5)
        self.inverter_warning=ttk.Label(root,wraplength=690)
        self.inverter_warning.grid(row=5,column=0,columnspan=3,sticky="w",padx=8,pady=8)
        self._show_inverter_type()

    def _show_inverter_type(self)->None:
        if not hasattr(self,"deye_frame"):return
        if self.inverter_type.get()=="deye":
            # The very first Deye installation must include dual-OTA partitions.
            # Never silently apply Deye APP-ONLY to a blank S3.
            self.volt_mode.set("factory")
            self.deye_frame.grid()
            self.inverter_warning.configure(text="Deye : ESP32 → Wi-Fi → clé Solarman. "
                "Ne pas flasher sur un ESP VPN, JK ou Voltronic en service.")
            if self.volt_fields["display_name"].get()=="Onduleur Voltronic":
                self.volt_fields["display_name"].set("Onduleur Deye")
        else:
            # Preserve working Voltronic readers by preferring APP-ONLY.
            self.volt_mode.set("update")
            self.deye_frame.grid_remove()
            self.inverter_warning.configure(text="Voltronic : RS232 avec MAX3232 ; "
                "jamais directement sur les GPIO 3,3 V.")
            if self.volt_fields["display_name"].get()=="Onduleur Deye":
                self.volt_fields["display_name"].set("Onduleur Voltronic")

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

    @staticmethod
    def _bundled_deye_image(mode:str)->Path:
        base=Path(getattr(sys,"_MEIPASS",Path(__file__).resolve().parent))
        names={"factory":"smart-energy-deye-S3-FACTORY-0x0.bin",
               "update":"smart-energy-deye-S3-APP-0x10000.bin"}
        if mode not in names:raise ValueError("Mode flash Deye invalide.")
        file=base/"embedded_firmware"/names[mode]
        if not file.is_file():
            raise ValueError("Firmware Deye précompilé absent du flasher Windows.")
        return file

    def _deye_values(self)->dict[str,str]:
        from deye_usb import deye_config_line
        values={k:v.get() for k,v in self.volt_fields.items()}
        values.update({k:v.get() for k,v in self.deye_fields.items()})
        deye_config_line(values)
        self.secret_values=[values.get("wifi_password",""),values.get("read_code","")]
        return values

    def _voltronic_values(self) -> dict[str,str]:
        from voltronic_usb import voltronic_config_line
        values={key:variable.get() for key,variable in self.volt_fields.items()}
        voltronic_config_line(values)  # Strict validation, no network.
        self.secret_values=[values.get("wifi_password",""),
                            values.get("read_code","")]
        return values

    def _flash_voltronic(self) -> None:
        try:
            family=self.inverter_type.get()
            if family not in ("voltronic","deye"):
                raise ValueError("Famille d'onduleur inconnue.")
            from vpn_usb import check_precompiled_image
            port=self._assert_ready()
            mode=self.volt_mode.get()
            values=(self._deye_values() if family=="deye" else self._voltronic_values()) if mode=="factory" else None
            image=(Path(self.volt_image.get().strip())
                   if self.volt_image.get().strip()
                   else self._bundled_deye_image(mode) if family=="deye"
                   else self._bundled_voltronic_image(mode))
            check_precompiled_image(image,mode,chip="esp32s3")
            if not messagebox.askyesno(f"Flash {family.upper()} S3",
                "Vérifie la puce ESP32-S3 N16R8 et le port COM.\n"
                "FACTORY : CARTE VIERGE uniquement ; peut remplacer les partitions.\n"
                "APP-ONLY conserve Wi-Fi/code sans réassociation USB.\n"
                 "Jamais sur un ESP32 JK ou VPN déjà associé. Continuer ?"):
                return
            self._run_async(
                lambda:self._do_voltronic(port,image,mode,values,family=family),
                mode="voltronic")
        except Exception as exc:
            messagebox.showerror("Onduleur S3",str(exc))

    def _do_voltronic(self,port:str,image:Path,mode:str,
                      values:dict[str,str]|None,family:str="voltronic")->None:
        from vpn_usb import flash_precompiled
        from voltronic_usb import provision_voltronic
        from deye_usb import provision_deye
        ensure_esptool_console_streams()
        self._progress_stage(f"Écriture du firmware {family} S3…")
        with monitor_esptool_progress(self._flash_percent):
            flash_precompiled(port,image,mode,self._log,chip="esp32s3")
        if mode=="update":
            self._progress_step(98,
                "APP-ONLY installé : NVS conservée, Wi-Fi et code inchangés.")
            return
        if values is None:
            raise ValueError("Configuration USB requise pour FACTORY.")
        self._progress_step(80,"Firmware S3 chargé, configuration USB…")
        if family=="deye":provision_deye(port,values,self._log)
        else:provision_voltronic(port,values,self._log)
        self._progress_step(98,f"{family.upper()} S3 : confirmation USB reçue.")

    @staticmethod
    def _bundled_voltronic_ota_folder()->Path:
        base=Path(getattr(sys,"_MEIPASS",Path(__file__).resolve().parent))
        from voltronic_ota_migration import validate_migration_images
        folder=base/"embedded_firmware"
        validate_migration_images(folder)
        return folder

    def _migrate_voltronic_to_ota(self)->None:
        """Explicit one-time USB partition migration; never plain APP-ONLY."""
        try:
            from voltronic_ota_migration import (
                flash_voltronic_ota_migration,validate_migration_images)
            if self.inverter_type.get()!="voltronic":
                raise ValueError("Cette migration est réservée au lecteur Voltronic ESP32-S3.")
            port=self._assert_ready()
            folder=self._bundled_voltronic_ota_folder()
            validate_migration_images(folder)
            if not messagebox.askyesno("Préparer OTA Voltronic · USB",
                "Ton firmware 0.3.7-test-ha-bridge peut fonctionner mais avoir "+
                "une seule partition d'application. Une APP-ONLY NE crée pas "+
                "de partitions OTA.\\n\\nCette opération USB crée les "+
                "partitions OTA en conservant l'adresse NVS. Elle demande "+
                "une sauvegarde privée et vérifie l'identité Voltronic "+
                "sur le vrai ESP32-S3. Le processus peut nécessiter une "+
                "récupération USB si une écriture échoue.\\n\\nContinuer ?"):
                return
            from datetime import datetime
            backup_name=("SmartEnergy-VOLTRONIC-NVS-PRIVE-"+port+"-"+
                datetime.now().strftime("%Y%m%d-%H%M%S")+".bin")
            chosen=filedialog.asksaveasfilename(
                title="Sauvegarde NVS PRIVÉE obligatoire · Wi-Fi et code Voltronic",
                defaultextension=".bin",filetypes=[("NVS privée",".bin")],
                initialdir=str(Path.home()/"Documents"),
                initialfile=backup_name)
            if not chosen:return
            backup=Path(chosen)
            if backup.exists():
                raise ValueError("La sauvegarde choisie existe déjà. Choisis un nouveau nom.")
            if not messagebox.askyesno("Dernière confirmation · S3 Voltronic",
                f"Sur le port {port} : lecture partitions, sauvegarde NVS privée, "+
                "puis flash séparé du bootloader 0x0, table OTA 0x8000, "+
                "application 0x10000. Aucun erase_flash. "+
                "Cette opération ne concerne ni JK, ni Deye, ni VPN.\\n\\n"+
                "Lancer la vérification et la migration ?"):
                return
            self._run_async(
                lambda:flash_voltronic_ota_migration(port,folder,backup,self._log),
                mode="migration-ota")
        except Exception as ex:
            messagebox.showerror("Migration OTA Voltronic",str(ex))

    def _configure_voltronic_only(self)->None:
        try:
            from voltronic_usb import provision_voltronic
            from deye_usb import provision_deye
            family=self.inverter_type.get()
            port=self._assert_ready()
            values=self._deye_values() if family=="deye" else self._voltronic_values()
            if not messagebox.askyesno("Reconfiguration USB Onduleur S3",
                "Redémarre le S3 et maintiens BOOT 3 secondes "
                "dans les 9 premières secondes, sans flasher. Continuer ?"):
                return
            fn=provision_deye if family=="deye" else provision_voltronic
            self._run_async(lambda:fn(port,values,self._log),mode="config")
        except Exception as exc:
            messagebox.showerror("Configuration Onduleur S3",str(exc))

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
            # The signed-off images from CI are bundled; USB provisioning only on FACTORY.
            mode=self.jk_mode.get()
            factory_cfg={key:var.get() for key,var in self.jk_fields.items()} if mode=="factory" else None
            if factory_cfg is not None:
                from jk_usb import jk_config_line
                jk_config_line(factory_cfg)
                self.secret_values=[factory_cfg["wifi_password"],factory_cfg["read_code"]]
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
            private_backup=None
            if not factory:
                # APP-ONLY fallback for readers which cannot yet accept OTA.
                # Never flash before reading the real table and backing up NVS.
                from datetime import datetime
                suggested="SmartEnergy-JK-NVS-PRIVE-"+port+"-"+datetime.now().strftime("%Y%m%d-%H%M%S")+".bin"
                target=filedialog.asksaveasfilename(
                    title="Sauvegarde obligatoire : Wi-Fi, code et associations JK (PRIVÉ)",
                    defaultextension=".bin",
                    filetypes=[("Sauvegarde ESP32 NVS", "*.bin")],
                    initialdir=str(Path.home()/"Documents"),
                    initialfile=suggested)
                if not target:return
                private_backup=Path(target)
                if private_backup.exists():
                    raise ValueError("Le fichier de sauvegarde existe déjà. Choisis un nouveau nom.")
                if not messagebox.askyesno(
                    "JK · migration USB sécurisée",
                    "Le lecteur ne peut pas être mis à jour en OTA ?\\n\\n"
                    "1. Lecture de sa table de partitions actuelle.\\n"
                    "2. Sauvegarde NVS (Wi-Fi, code, BMS) sur TON PC.\\n"
                    "3. APP-ONLY 0x10000 seulement, sans effacement NVS.\\n\\n"
                    "La sauvegarde contient des informations privées :\\n"
                    "ne la mets pas dans un dépôt public ou dans ChatGPT.\\n"
                    "Si la table est inconnue, le flash est refusé.\\n\\n"
                    "Confirmer cette mise à jour USB JK ?"):
                    return
            self._run_async(lambda: self._do_jk(port,path,offset,private_backup,factory_cfg),
                            mode="jk")
        except Exception as exc:
            messagebox.showerror("Flash JK", str(exc))

    def _do_jk(self, port: str, file: Path, offset: str,
               private_backup: Path | None = None,
               factory_cfg: dict[str,str] | None = None) -> None:
        ensure_esptool_console_streams()
        self._log(f"Flash JK {port}, offset {offset}, fichier {file.name}")
        from esptool import main as esptool_main
        if offset == "0x10000":
            from jk_usb_guard import backup_jk_nvs
            if private_backup is None:
                raise ValueError("Sauvegarde NVS obligatoire avant la mise à jour USB JK.")
            self._progress_stage("JK : lecture des partitions et sauvegarde NVS PRIVÉE…")
            checked=backup_jk_nvs(port,private_backup,file.stat().st_size,esptool_main,
                                  progress=self._progress_step)
            self._log("Table des partitions validée ; sauvegarde NVS conservée "+
                "sur ton PC uniquement. Aucun code ou mot de passe affiché.")
            if checked.has_second_ota_slot:
                self._log("Double partition OTA détectée. Si une autre partition "+
                    "était active, vérifie la version après redémarrage.")
            else:
                self._log("Partition unique : APP-ONLY maintient la configuration, "+
                    "mais ne crée PAS de partition OTA supplémentaire.")
        self._progress_stage("Connexion à l'ESP32 JK et écriture du firmware…")
        with monitor_esptool_progress(self._flash_percent):
            esptool_main(["--chip", "esp32", "--port", port, "--baud", "115200",
                          # USB backup leaves the ROM/stub in download mode.
                          # DO NOT reset/re-enter bootloader for the actual write.
                          * (["--before", "no_reset"] if offset=="0x10000" else []),
                          "--after", "hard_reset",
                          "write_flash", "--flash_mode", "dio", "--flash_freq", "40m",
                          "--flash_size", "4MB", offset, str(file)])
        if offset=="0x0" and factory_cfg:
            from jk_usb import provision_jk
            self._progress_step(80,"JK : configuration initiale USB…")
            provision_jk(port,factory_cfg,self._log)
        self._progress_step(98, "Image JK écrite et vérifiée.")
        self._log("Flash JK terminé. Redémarre le lecteur et vérifie les BMS dans l'APK.")

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
        # The secure NVS name is only a display identity in Tailscale.
        values["gateway_name"]=values.get("gateway_name","").strip().lower()
        # New discovery-only flash: no legacy IP address entry anywhere in UI.
        for family in ("jk","volt"):
            for slot in (1,2,3):
                values[f"{family}{slot}_ip"]=""
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

    def _vpn_migration_images(self) -> Path:
        from vpn_ota_migration import validate_images
        folder=Path(getattr(sys,"_MEIPASS",Path(__file__).resolve().parent)) / "embedded_firmware"
        validate_images(folder)
        return folder

    def _migrate_vpn_to_ota(self) -> None:
        """One-time physically guarded USB migration, no credentials rewritten."""
        try:
            from vpn_ota_migration import prepare_usb_migration
            port=self._assert_ready()
            if self.vpn_chip.get()!="esp32s3":
                raise ValueError("Migration OTA VPN réservée à ESP32-S3 N16R8.")
            image_dir=self._vpn_migration_images()
            if not messagebox.askyesno(
                "VPN S3 : préparer OTA sans perdre les paramètres",
                "Le service OTA est absent (HTTP 404) sur certains anciens VPN.\n\n"
                "Cette opération USB est UNIQUEMENT destinée à un ESP32-S3 "
                "dont la table est SINGLE_APP_LARGE et reconnaissable.\n\n"
                "Le flasher lira et sauvegardera l'ancien firmware, sa table "
                "de partitions ET la NVS privée (identité Tailscale, Wi-Fi, "
                "token, noms et codes JK/Voltronic), puis vérifiera les copies.\n"
                "Si la disposition actuelle est déjà OTA ou inconnue, il "
                "refusera sans rien modifier.\n\n"
                "Aucun erase_flash ni réassociation ne sera exécuté. Continuer ?"):
                return
            folder=filedialog.askdirectory(
                title="Choisir le dossier PARENT pour la sauvegarde VPN PRIVÉE")
            if not folder:return
            backup=Path(folder)/(
                "SmartEnergy-VPN-SAUVEGARDE-PRIVEE-"+port+"-"+
                datetime.now().strftime("%Y%m%d-%H%M%S"))
            if backup.exists():
                raise ValueError("Le dossier privé existe déjà : en choisir un autre.")
            if not messagebox.askyesno(
                "Confirmation physique VPN S3",
                "Vérifie que le port "+port+" correspond bien à la PASSERELLE VPN, "
                "et non à un ESP JK ou Voltronic.\n\n"
                "L'opération commence par une lecture seule, vérifie l'identité "
                "et la partition NVS à 0x9000 ; elle s'arrête au moindre doute. "
                "Si toutes les vérifications passent, elle écrit le bootloader, "
                "la table OTA et l'application 0.3.14, puis relit la NVS.\n\n"
                "Tu dois conserver le dossier de sauvegarde privé.\n\n"
                "Confirmer la migration USB ?"):
                return
            self._run_async(
                lambda:prepare_usb_migration(port,image_dir,backup,self._log),
                mode="vpn-ota-migration")
        except Exception as ex:
            messagebox.showerror("Migration OTA VPN",str(ex))

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

    def _patch_jk_vpn(self) -> None:
        """Repair one saved JK LAN target; never replace VPN credentials."""
        try:
            from vpn_usb import update_jk_target_over_serial, format_jk_target_patch
            port=self._assert_ready()
            slot=int(self.vpn_jk_patch_slot.get())
            ip=self.fields[f"jk{slot}_ip"].get().strip()
            code=self.fields[f"jk{slot}_code"].get()
            format_jk_target_patch(slot,ip,code)
            if not messagebox.askyesno(
                "VPN · corriger uniquement le JK",
                f"Corriger l'adresse IP du lecteur ESP JK{slot} dans la passerelle VPN ?\n\n"
                "Seule la cible JK choisie sera modifiée.\n"
                "Si le code est vide, le code JK déjà enregistré sera conservé.\n"
                "Wi-Fi, Tailscale, token VPN, autorisation des écritures, "
                "autres JK et Voltronic restent inchangés.\n\n"
                "Cette opération nécessite le firmware ESP VPN 0.3.8 ou supérieur "
                "et un accès USB physique BOOT, sans reflash JK."
            ): return
            self.secret_values=[code] if code else []
            self._run_async(
                lambda:update_jk_target_over_serial(
                    port,slot,ip,code,self._log,
                    on_ready=lambda:self._progress_step(
                        88,f"ESP VPN : mise à jour ciblée de JK{slot}…")),
                mode="config")
        except Exception as exc:
            messagebox.showerror("VPN · IP JK",str(exc))

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
                "ATTENTION : cette opération remplace TOUTE la configuration "
                "Wi-Fi, Tailscale et lecteurs JK. Pour un seul onduleur, "
                "ne remplace pas toute cette configuration. Continuer ?"
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
        bar = (5 + round(70*percent/100)
               if self.operation_mode in ("vpn","voltronic")
               else 30 + round(65*percent/100))
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
