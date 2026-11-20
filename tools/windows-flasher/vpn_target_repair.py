#!/usr/bin/env python3
"""Repair a pre-existing Smart Energy VPN reader mapping over PHYSICAL USB.

This utility cannot flash firmware, replace Wi-Fi, alter Tailscale, or reset JK.
Only SEVPN_ADD_VOLTRONIC is allowed, followed by firmware NVS readback.
"""
from __future__ import annotations

import queue
import threading
import tkinter as tk
from tkinter import messagebox, ttk

import serial.tools.list_ports
from vpn_usb import format_voltronic_patch, provision_over_serial

APP_NAME = "Smart Energy — Réparation VPN Onduleurs (sans flash)"


class VpnTargetRepair(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(APP_NAME)
        self.geometry("760x565")
        self.minsize(620, 490)
        self.port = tk.StringVar()
        self.slot = tk.StringVar(value="1")
        self.ip = tk.StringVar()
        self.code = tk.StringVar()
        self.busy = False
        self.events = queue.Queue()
        root = ttk.Frame(self, padding=16)
        root.pack(fill="both", expand=True)
        root.columnconfigure(1, weight=1)
        ttk.Label(root, text="Réparer la passerelle VPN sans reflasher",
                  font=("Segoe UI", 14, "bold")).grid(
                      row=0, column=0, columnspan=3, sticky="w", pady=(0, 8))
        ttk.Label(root, text=(
            "Le VPN conserve son Wi-Fi, Tailscale, son token, tous les JK et "
            "les autres onduleurs. Un seul emplacement est corrigé par opération. "
            "Le firmware vérifie l'enregistrement en NVS avant de confirmer."),
            wraplength=700, justify="left").grid(
                row=1, column=0, columnspan=3, sticky="w", pady=(0, 12))
        ttk.Label(root, text="Port USB du seul ESP32 VPN :").grid(
            row=2, column=0, sticky="w")
        self.port_list = ttk.Combobox(root, textvariable=self.port,
                                      state="readonly", width=35)
        self.port_list.grid(row=2, column=1, sticky="ew", padx=8, pady=5)
        ttk.Button(root, text="Actualiser", command=self.refresh_ports).grid(
            row=2, column=2, padx=4)
        ttk.Label(root, text="Onduleur :").grid(row=3, column=0, sticky="w")
        ttk.Combobox(root, textvariable=self.slot, values=("1", "2", "3"),
                     state="readonly", width=6).grid(
                         row=3, column=1, sticky="w", padx=8, pady=5)
        ttk.Label(root, text="IP locale de l'ESP32 Voltronic :").grid(
            row=4, column=0, sticky="w")
        ttk.Entry(root, textvariable=self.ip).grid(
            row=4, column=1, sticky="ew", padx=8, pady=5)
        ttk.Label(root, text="Code de lecture de cet ESP32 :").grid(
            row=5, column=0, sticky="w")
        ttk.Entry(root, textvariable=self.code, show="•").grid(
            row=5, column=1, sticky="ew", padx=8, pady=5)
        ttk.Label(root, text=(
            "Branche le VPN au PC avec un câble USB données. Démarre la "
            "réparation, puis maintiens BOOT ~4 secondes et relâche, sans "
            "appuyer sur RESET. Sur un ancien firmware, une fenêtre BOOT au "
            "démarrage peut être nécessaire. Si la réponse USB SEVPN_PONG "
            "n'est pas reconnue, aucune configuration ne sera envoyée."),
            wraplength=690, justify="left").grid(
                row=6, column=0, columnspan=3, sticky="w", pady=11)
        self.go = ttk.Button(root, text="Enregistrer cet INV dans le VPN — AUCUN FLASH",
                             command=self.repair)
        self.go.grid(row=7, column=0, columnspan=3, sticky="ew", pady=8)
        self.status = ttk.Label(root, text="Prêt : sélectionner le port USB du VPN.",
                                wraplength=680)
        self.status.grid(row=8, column=0, columnspan=3, sticky="w", pady=6)
        self.journal = tk.Text(root, height=6, wrap="word", state="disabled")
        self.journal.grid(row=9, column=0, columnspan=3, sticky="nsew", pady=5)
        root.rowconfigure(9, weight=1)
        self.refresh_ports()
        self.after(100, self.drain)

    def refresh_ports(self):
        values = [p.device for p in serial.tools.list_ports.comports()
                  if p.device.upper().startswith("COM")]
        self.port_list["values"] = values
        if values and self.port.get() not in values:
            self.port.set(values[0])
        if not values:
            self.port.set("")

    def repair(self):
        if self.busy:
            return
        port = self.port.get().strip().upper()
        if port not in self.port_list["values"]:
            messagebox.showerror("Port USB", "Sélectionne le port COM de l'ESP32 VPN.")
            return
        try:
            slot = int(self.slot.get())
            address = self.ip.get().strip()
            secret = self.code.get()
            payload = format_voltronic_patch(slot, address, secret)
        except (ValueError, TypeError) as exc:
            messagebox.showerror("Configuration Voltronic", str(exc))
            return
        if not messagebox.askyesno(
            "Confirmation — USB uniquement",
            f"Corriger UNIQUEMENT INV{slot} sur la passerelle VPN ?\n\n"
            f"IP locale : {address}\n"
            "Le code reste masqué. Les JK, Wi-Fi et Tailscale sont conservés.\n"
            "Le firmware doit confirmer SEVPN_OK_REBOOT après relecture NVS.\n\n"
            "Cette opération n'écrit AUCUN firmware sur la mémoire flash."
        ):
            return
        self.busy = True
        self.go.configure(state="disabled")
        self.status.configure(text=f"INV{slot} : connexion USB / BOOT en cours…")
        def safe_log(message: str):
            # Never disclose the private access code in logs or errors.
            self.events.put(("log", str(message).replace(secret, "[MASQUÉ]")))
        def job():
            try:
                # Safe control channel: no flashing functions are imported.
                provision_over_serial(port, None, safe_log, timeout_seconds=60,
                                      payload_override=payload)
                self.events.put(("done", f"INV{slot} enregistré et vérifié en NVS. "
                                         "La passerelle VPN redémarre."))
            except Exception as exc:
                self.events.put(("error", str(exc).replace(secret, "[MASQUÉ]")))
        threading.Thread(target=job, daemon=True).start()

    def drain(self):
        try:
            while True:
                kind, message = self.events.get_nowait()
                if kind == "log":
                    self.journal.configure(state="normal")
                    self.journal.insert("end", message + "\n")
                    self.journal.see("end")
                    self.journal.configure(state="disabled")
                else:
                    self.busy = False
                    self.go.configure(state="normal")
                    self.code.set("")
                    self.status.configure(text=message)
                    if kind == "done":
                        messagebox.showinfo("Configuration enregistrée", message)
                    else:
                        messagebox.showerror("État non confirmé", message)
        except queue.Empty:
            pass
        self.after(100, self.drain)


if __name__ == "__main__":
    VpnTargetRepair().mainloop()
