#!/usr/bin/env python3
"""Listen-only local discovery diagnostic for Smart Energy.

Receives unsigned-to-this-tool UDP discovery messages, and listens to mDNS.
Never scans IP addresses, calls an ESP API, stores tokens, or changes settings.
A structurally valid UDP beacon is NOT proof its HMAC was authenticated by VPN.
"""
from __future__ import annotations

import ipaddress
import json
import queue
import socket
import threading
import tkinter as tk
from tkinter import ttk

from zeroconf import ServiceBrowser, ServiceStateChange, Zeroconf

DISCOVERY_PORT = 18769
CAPTURE_SECONDS = 60


def parse_beacon(packet: bytes, sender_ip: str):
    """Return (family, name, id) for *structural* beacons; no HMAC verification."""
    if len(packet) < 60 or len(packet) > 1024:
        return None
    try:
        source = ipaddress.IPv4Address(sender_ip)
        if not source.is_private:
            return None
        doc = json.loads(packet.decode("utf-8"))
        if not isinstance(doc, dict) or doc.get("proto") != "smart-energy-discovery-v1":
            return None
        family = doc.get("family")
        if family not in ("voltronic", "jk", "inverter"):
            return None
        port = doc.get("port")
        if type(port) is not int or port != (80 if family == "jk" else 18081):
            return None
        if doc.get("ip") != sender_ip:
            return None
        name = doc.get("name")
        identity = doc.get("id")
        tag = doc.get("auth")
        if not isinstance(name, str) or not 1 <= len(name) <= 32:
            return None
        if not isinstance(identity, str) or not 6 <= len(identity) <= 47:
            return None
        if any(ord(c) < 32 or c == "|" for c in name):
            return None
        if not isinstance(tag, str) or len(tag) != 64:
            return None
        if any(c not in "0123456789abcdef" for c in tag):
            return None
        return family, name, identity
    except (ValueError, UnicodeError, TypeError, OverflowError):
        return None


def parse_mdns_name(service_name: str, properties: dict):
    """Only label recognizable Smart Energy mDNS services."""
    raw = service_name.removesuffix("._http._tcp.local.")
    if " (sevolt-" in raw.lower():
        return "voltronic", raw.split(" (", 1)[0]
    if raw.lower().startswith("sevolt-"):
        return "voltronic", raw
    if raw.lower().startswith("smartjk-"):
        return "jk", raw
    family = properties.get(b"family") or properties.get("family")
    if family == b"voltronic" or family == "voltronic":
        return "voltronic", raw
    return None


class Diagnostic(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Smart Energy — Diagnostic découverte ESP (lecture seule)")
        self.geometry("850x580")
        self.minsize(690, 490)
        self.events = queue.Queue()
        self.records = {}
        self.capturing = False
        self.udp_socket = None
        self.zeroconf = None
        self.browser = None
        self.seconds_left = CAPTURE_SECONDS

        root = ttk.Frame(self, padding=14)
        root.pack(fill="both", expand=True)
        root.columnconfigure(0, weight=1)
        ttk.Label(root, text="Découverte locale : UDP et mDNS",
                  font=("Segoe UI", 15, "bold")).grid(row=0, column=0, sticky="w")
        ttk.Label(root, text=(
            "Écoute uniquement les annonces du réseau local. Aucun code, "
            "aucune IP ni mot de passe à saisir. Aucune modification des ESP, "
            "du VPN ou de Home Assistant. Laisser tourner 60 secondes."),
            wraplength=800, justify="left").grid(
                row=1, column=0, sticky="w", pady=(8, 10))
        bar = ttk.Frame(root)
        bar.grid(row=2, column=0, sticky="ew", pady=(0, 8))
        self.start_btn = ttk.Button(bar, text="Démarrer l'écoute (60 s)",
                                    command=self.start)
        self.start_btn.pack(side="left", padx=(0, 8))
        self.stop_btn = ttk.Button(bar, text="Arrêter", command=self.stop,
                                   state="disabled")
        self.stop_btn.pack(side="left")
        self.status = ttk.Label(root, text="Prêt. PC connecté au réseau de la maison.")
        self.status.grid(row=3, column=0, sticky="w", pady=6)

        table_frame = ttk.Frame(root)
        table_frame.grid(row=4, column=0, sticky="nsew")
        root.rowconfigure(4, weight=1)
        columns = ("canal", "family", "name", "identities", "messages")
        self.table = ttk.Treeview(table_frame, columns=columns, show="headings",
                                  height=10)
        headings = {
            "canal": ("Canal", 105), "family": ("Famille", 110),
            "name": ("Nom annoncé", 290), "identities": ("Identités", 100),
            "messages": ("Annonces", 95)
        }
        for key, (heading, width) in headings.items():
            self.table.heading(key, text=heading)
            self.table.column(key, width=width, minwidth=70)
        self.table.pack(fill="both", expand=True)
        ttk.Label(root, text=(
            "mDNS = ce qu'Android recherche. UDP 18769 = annonce que le VPN "
            "doit vérifier par nom et code. La présence UDP seule NE prouve "
            "pas que le code VPN est correct."),
            wraplength=790, justify="left").grid(
                row=5, column=0, sticky="w", pady=(12, 6))
        self.summary = ttk.Label(root, text="Aucune annonce enregistrée.",
                                 wraplength=790)
        self.summary.grid(row=6, column=0, sticky="w", pady=4)
        ttk.Label(root, text=(
            "Windows : si le pare-feu demande l'autorisation, choisir "
            "uniquement « Réseaux privés ». Si aucun équipement n'apparaît, "
            "le diagnostic reste non concluant (pare-feu ou carte réseau). "
            "Tu peux envoyer une capture de cette fenêtre sans exposer les codes."),
            wraplength=790, justify="left").grid(
                row=7, column=0, sticky="w", pady=7)
        self.protocol("WM_DELETE_WINDOW", self.exit)
        self.after(100, self.drain)

    def start(self):
        if self.capturing:
            return
        self.records.clear()
        for item in self.table.get_children():
            self.table.delete(item)
        self.summary.configure(text="Écoute en cours…")
        self.seconds_left = CAPTURE_SECONDS
        self.capturing = True
        self.start_btn.configure(state="disabled")
        self.stop_btn.configure(state="normal")
        threading.Thread(target=self.listen_udp, daemon=True).start()
        threading.Thread(target=self.listen_mdns, daemon=True).start()
        self.tick()

    def listen_udp(self):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("0.0.0.0", DISCOVERY_PORT))
            sock.settimeout(1.0)
            self.udp_socket = sock
            while self.capturing:
                try:
                    packet, address = sock.recvfrom(1024)
                except socket.timeout:
                    continue
                except OSError:
                    break
                record = parse_beacon(packet, address[0])
                if record:
                    family, name, identity = record
                    self.events.put(("record", ("UDP 18769", family, name, identity)))
        except OSError:
            self.events.put(("error", "Port UDP 18769 indisponible ou bloqué."))
        finally:
            if self.udp_socket is sock:
                self.udp_socket = None
            sock.close()

    def listen_mdns(self):
        try:
            zc = Zeroconf()
            self.zeroconf = zc

            def on_service(zc_instance, service_type, name, state):
                if not self.capturing or state == ServiceStateChange.Removed:
                    return
                try:
                    info = zc_instance.get_service_info(service_type, name,
                                                        timeout=1500)
                    record = parse_mdns_name(name, info.properties if info else {})
                    if record:
                        family, display_name = record
                        self.events.put(("record", ("mDNS", family, display_name, name)))
                except Exception:
                    return

            self.browser = ServiceBrowser(zc, "_http._tcp.local.",
                                          handlers=[on_service])
        except Exception:
            self.events.put(("error", "mDNS indisponible sur ce PC."))
            if self.zeroconf is not None:
                self.zeroconf.close()
                self.zeroconf = None

    def tick(self):
        if not self.capturing:
            return
        self.seconds_left -= 1
        self.status.configure(text=(
            f"Écoute active — encore {self.seconds_left} s. "
            "Garde le PC sur le réseau local."))
        if self.seconds_left <= 0:
            self.stop()
        else:
            self.after(1000, self.tick)

    def stop(self):
        if not self.capturing:
            return
        self.capturing = False
        self.start_btn.configure(state="normal")
        self.stop_btn.configure(state="disabled")
        self.status.configure(text="Écoute terminée — capture prête.")
        if self.udp_socket is not None:
            try:
                self.udp_socket.close()
            except OSError:
                pass
        if self.zeroconf is not None:
            self.zeroconf.close()
            self.zeroconf = None
        self.browser = None

    def drain(self):
        try:
            while True:
                kind, content = self.events.get_nowait()
                if kind == "error":
                    self.status.configure(text=str(content))
                    continue
                canal, family, name, identity = content
                key = (canal, family, name)
                record = self.records.setdefault(key, {"ids": set(), "count": 0})
                record["ids"].add(identity)
                record["count"] += 1
                row_id = "|".join(key)
                values = (canal, family, name, len(record["ids"]), record["count"])
                if self.table.exists(row_id):
                    self.table.item(row_id, values=values)
                else:
                    self.table.insert("", "end", iid=row_id, values=values)
                udps = sum(1 for c, fam, _ in self.records if c == "UDP 18769"
                           and fam == "voltronic")
                mdns = sum(1 for c, fam, _ in self.records if c == "mDNS"
                           and fam == "voltronic")
                self.summary.configure(text=(
                    f"Onduleurs distincts annoncés : UDP {udps}, mDNS {mdns}. "
                    "Si '5.6' apparaît mais pas ESP1, la différence de "
                    "découverte est confirmée sur ce PC."))
        except queue.Empty:
            pass
        self.after(100, self.drain)

    def exit(self):
        self.stop()
        self.destroy()


if __name__ == "__main__":
    Diagnostic().mainloop()
