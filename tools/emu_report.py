#!/usr/bin/env python3
"""Schreibt report.md einer Android-Version aus results.json und gibt die Zeile fuer summary.md aus."""
import json, os, sys
d, api, name, secs, version, accel = sys.argv[1:7]
rows = json.load(open(os.path.join(d, "results.json"))) if os.path.exists(os.path.join(d, "results.json")) else []
ok = sum(1 for r in rows if r["status"] == "BESTANDEN"); bad = sum(1 for r in rows if r["status"] == "FEHLER")
crash = any(r["id"] == "S7" and r["status"] == "FEHLER" for r in rows)
L = [f"# {name} (API {api}), ChatLens {version}", "", f"Beschleunigung: {accel}. Start bis sys.boot_completed: {secs} s.", "",
     "Spalte Art: BELEGT heisst im Emulator beobachtet. TEILBELEG heisst nur ein Teil ist beobachtet. HYPOTHESE bleibt fuer das Geraet offen.", "",
     "| Nr | Szenario | Ergebnis | Art | Beleg |", "|---|---|---|---|---|"]
for r in rows:
    ev = r["evidence"].replace("|", "/").replace("\n", " ")[:400]
    L.append(f"| {r['id']} | {r['title']} | {r['status']} | {r['kind']} | {ev} |")
L += ["", "## Bildschirmfotos", ""]
for r in rows:
    for s in r["shots"]:
        L.append(f"![{r['id']}]({s})")
L += ["", "## Was der Emulator nicht ersetzt", "",
      "- HyperOS 3 auf dem Xiaomi 15 Ultra: Systemoberflaeche, Benachrichtigungsleiste, Energiesparen, Pop-up-Regeln und Fenstersteuerung weichen von Android 13 bis 16 im Emulator ab.",
      "- Echtes WhatsApp: FakeWA bildet nur die IDs und Struktur nach, die das Profil kennt (nicht belegt, ob WhatsApp heute so aussieht).",
      "- Gemma und Parakeet: nicht gemessen; Tests nutzen einen Mock-Server oder kein Modell.",
      "- Leistung: Ohne KVM laeuft der Emulator per Software (TCG), Zeiten sind nicht mit einem Telefon vergleichbar."]
open(os.path.join(d, "report.md"), "w").write("\n".join(L) + "\n")
print(f"| {name} | {api} | {secs} s | {ok} | {bad} | {'ja' if crash else 'nein'} |")
