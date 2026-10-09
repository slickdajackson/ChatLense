#!/usr/bin/env python3
"""Writes report.md for one Android version from results.json and prints the summary.md row."""
import json, os, sys
d, api, name, secs, version, accel = sys.argv[1:7]
rows = json.load(open(os.path.join(d, "results.json"))) if os.path.exists(os.path.join(d, "results.json")) else []
ok = sum(1 for r in rows if r["status"] == "BESTANDEN"); bad = sum(1 for r in rows if r["status"] == "FEHLER")
crash = any(r["id"] == "S7" and r["status"] == "FEHLER" for r in rows)
L = [f"# {name} (API {api}), ChatLens {version}", "", f"Acceleration: {accel}. Startup until sys.boot_completed: {secs} s.", "",
     "Column Kind: BELEGT means observed in the emulator. TEILBELEG means only part of it was observed. HYPOTHESE stays open for a real device.", "",
     "| No | Scenario | Result | Kind | Evidence |", "|---|---|---|---|---|"]
for r in rows:
    ev = r["evidence"].replace("|", "/").replace("\n", " ")[:400]
    L.append(f"| {r['id']} | {r['title']} | {r['status']} | {r['kind']} | {ev} |")
L += ["", "## Screenshots", ""]
for r in rows:
    for s in r["shots"]:
        L.append(f"![{r['id']}]({s})")
L += ["", "## What the emulator does not replace", "",
      "- HyperOS 3 on the Xiaomi 15 Ultra: the system UI, notification shade, battery saver, pop-up rules, and window controls differ from Android 13 to 16 in the emulator.",
      "- Real WhatsApp: FakeWA only reproduces the ids and structure the profile knows. It is not evidence that WhatsApp looks like this today.",
      "- Gemma and Parakeet: not measured. Tests use a mock server or no model.",
      "- Performance: without KVM the emulator runs in software (TCG). Timings are not comparable to a phone."]
open(os.path.join(d, "report.md"), "w").write("\n".join(L) + "\n")
print(f"| {name} | {api} | {secs} s | {ok} | {bad} | {'yes' if crash else 'no'} |")
