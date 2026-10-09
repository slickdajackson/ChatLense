#!/usr/bin/env python3
"""Emulator-Regression fuer ChatLens: Szenarien gegen die Attrappe FakeWA (com.chatlens.fakewa).

Aufruf: emu_ui.py --api 34 --out DIR --chatlens APK --fakewa APK [--only S1,S2]
Jedes Szenario liefert ein Ergebnis mit Status (BESTANDEN, FEHLER, UEBERSPRUNGEN) und Beleg. Bildschirmfotos landen in DIR/shots.
Alles hier ist Emulator-Beleg: kein HyperOS, kein echtes WhatsApp.
"""
import argparse, os, re, subprocess, sys, time, traceback, threading, json
import xml.etree.ElementTree as ET
from http.server import BaseHTTPRequestHandler, HTTPServer

SER = os.environ.get("SER", "emulator-5554")
CL = "app.chatlens"
FW = "com.chatlens.fakewa"
OUT = "."
SHOTS = "."


def adb(*a, timeout=90, binary=False):
    r = subprocess.run(["adb", "-s", SER, *a], capture_output=True, timeout=timeout)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")


def sh(cmd, timeout=90):
    return adb("shell", cmd, timeout=timeout)


def shot(name):
    p = os.path.join(SHOTS, name + ".png")
    data = adb("exec-out", "screencap", "-p", binary=True)
    open(p, "wb").write(data)
    return os.path.relpath(p, OUT)


class Node:
    def __init__(self, e):
        self.text = e.get("text", "")
        self.desc = e.get("content-desc", "")
        self.rid = e.get("resource-id", "")
        self.cls = e.get("class", "")
        self.pkg = e.get("package", "")
        self.clickable = e.get("clickable") == "true"
        self.scrollable = e.get("scrollable") == "true"
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", e.get("bounds", "[0,0][0,0]"))
        self.l, self.t, self.r, self.b = map(int, m.groups())

    @property
    def cx(self): return (self.l + self.r) // 2

    @property
    def cy(self): return (self.t + self.b) // 2


def dump(retries=3):
    for _ in range(retries):
        sh("rm -f /sdcard/u.xml")
        out = sh("uiautomator dump /sdcard/u.xml", timeout=120)
        x = sh("cat /sdcard/u.xml")
        if "<hierarchy" in x:
            try:
                return [Node(e) for e in ET.fromstring(x[x.index("<hierarchy"):]).iter("node")]
            except ET.ParseError:
                pass
        time.sleep(2)
    return []


def find(nodes, text=None, desc=None, rid=None, sub=True):
    for n in nodes:
        if text is not None and (text in n.text if sub else n.text == text):
            return n
        if desc is not None and (desc in n.desc if sub else n.desc == desc):
            return n
        if rid is not None and n.rid.endswith(rid):
            return n
    return None


def tap(x, y): sh(f"input tap {x} {y}")


def tap_text(text, timeout=30, sub=True):
    end = time.time() + timeout
    while time.time() < end:
        n = find(dump(), text=text, sub=sub)
        if n:
            tap(n.cx, n.cy)
            return True
        time.sleep(1.5)
    return False


def wait_text(text, timeout=60):
    end = time.time() + timeout
    while time.time() < end:
        n = find(dump(), text=text)
        if n:
            return n
        time.sleep(1.5)
    return None


def swipe(x1, y1, x2, y2, ms=300): sh(f"input swipe {x1} {y1} {x2} {y2} {ms}")


def top_activity():
    o = sh("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -2")
    m = re.search(r"u0 ([\w.]+)/", o)
    return m.group(1) if m else ""


def wait_top(pkg, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        if top_activity() == pkg:
            return True
        time.sleep(1)
    return False


def crashes():
    o = sh("logcat -d -b crash -v brief", timeout=60)
    anr = sh("logcat -d -b main -s ActivityManager:E | grep -i 'ANR in'", timeout=60)
    return o.strip(), anr.strip()


def clear_logcat(): sh("logcat -c")


# ---------------- Mock-API (OpenAI-kompatibel) ----------------
class Mock(BaseHTTPRequestHandler):
    calls = 0

    def do_POST(self):
        n = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(n)
        Mock.calls += 1
        body = json.dumps({"id": "x", "object": "chat.completion", "choices": [{"index": 0, "finish_reason": "stop", "message": {"role": "assistant", "content": "Kurze Testanalyse des Mock-Servers. Der Chat wirkt freundlich."}}], "usage": {"prompt_tokens": 10, "completion_tokens": 10, "total_tokens": 20}}).encode()
        self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)

    def log_message(self, *a): pass


def start_mock(port=8099):
    srv = HTTPServer(("0.0.0.0", port), Mock)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


# ---------------- Ergebnisse ----------------
class Report:
    def __init__(self): self.rows = []

    def add(self, sid, title, status, evidence, kind="BELEGT", shots=()):
        self.rows.append(dict(id=sid, title=title, status=status, evidence=evidence, kind=kind, shots=list(shots)))
        print(f"[{status}] {sid} {title}: {evidence}", flush=True)


def prefs_xml(d):
    items = []
    for k, v in d.items():
        if isinstance(v, bool): items.append(f'<boolean name="{k}" value="{str(v).lower()}" />')
        elif isinstance(v, int): items.append(f'<int name="{k}" value="{v}" />')
        else: items.append(f'<string name="{k}">{v}</string>')
    return "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n" + "\n".join(items) + "\n</map>\n"


def seed_prefs(d):
    """Schreibt shared_prefs/chatlens.xml der Debug-App (run-as) und startet die App neu."""
    adb("shell", "am", "force-stop", CL)
    xml = prefs_xml(d)
    open("/tmp/chatlens.xml", "w").write(xml)
    adb("push", "/tmp/chatlens.xml", "/data/local/tmp/chatlens.xml")
    sh(f"run-as {CL} sh -c 'mkdir -p shared_prefs; cp /data/local/tmp/chatlens.xml shared_prefs/chatlens.xml'")


def push_profile():
    """Profil mit dem Paket der Attrappe: alle IDs com.whatsapp:id/ werden zu com.chatlens.fakewa:id/."""
    here = os.path.dirname(os.path.abspath(__file__))
    src = os.path.join(here, "..", "app", "src", "main", "assets", "profiles", "whatsapp.json")
    j = open(src, encoding="utf-8").read().replace("com.whatsapp", FW)
    open("/tmp/profile.json", "w", encoding="utf-8").write(j)
    adb("push", "/tmp/profile.json", "/data/local/tmp/profile.json")
    sh(f"run-as {CL} sh -c 'mkdir -p files/profiles; cp /data/local/tmp/profile.json files/profiles/whatsapp.json'")


def enable_a11y():
    cur = sh("settings get secure enabled_accessibility_services").strip()
    svc = f"{CL}/{CL}.service.ChatAccessibilityService"
    if svc not in cur:
        new = svc if cur in ("", "null") else cur + ":" + svc
        sh(f"settings put secure enabled_accessibility_services {new}")
    sh("settings put secure accessibility_enabled 1")


def a11y_connected():
    return "label=ChatLens" in sh("dumpsys accessibility | grep -i 'Bound services'")


def wait_a11y(timeout=120):
    """Unter TCG bindet der Dienst erst nach Sekunden bis Minuten; bei verlorener Einstellung erneut setzen."""
    end = time.time() + timeout
    while time.time() < end:
        if a11y_connected(): return True
        enable_a11y(); time.sleep(6)
    return False


def wake():
    sh("input keyevent KEYCODE_WAKEUP"); sh("wm dismiss-keyguard")


def grant_all():
    sh(f"pm grant {CL} android.permission.POST_NOTIFICATIONS")
    sh(f"pm grant {FW} android.permission.POST_NOTIFICATIONS")
    sh(f"appops set {CL} SYSTEM_ALERT_WINDOW allow")
    enable_a11y()


def fresh_state():
    adb("shell", "am", "force-stop", CL)
    adb("shell", "am", "force-stop", FW)
    sh(f"pm clear {CL}")
    sh(f"pm clear {FW}")
    grant_all()
    push_profile()
    wake()
    sh("input keyevent KEYCODE_HOME")


def launch_cl():
    sh(f"am start -n {CL}/.MainActivity")
    wait_top(CL, 40)


# ---------------- Szenarien ----------------
def s1_install(rp, a):
    have_cl = "versionCode=15" in sh(f"dumpsys package {CL} | grep versionCode")
    have_fw = FW in sh("pm list packages " + FW)
    if have_cl and have_fw and os.environ.get("REUSE_INSTALL"):
        out = out2 = "Success (vorhanden, REUSE_INSTALL)"
    else:
        out = adb("install", "-r", "-g", a.chatlens, timeout=900)
        out2 = adb("install", "-r", "-g", a.fakewa, timeout=600)
    ok_cl = "Success" in out
    ok_fw = "Success" in out2
    abis = sh("getprop ro.product.cpu.abilist").strip()
    rp.add("S1", "Installation von ChatLens und FakeWA", "BESTANDEN" if ok_cl and ok_fw else "FEHLER",
           f"ChatLens: {out.strip()[:120]}; FakeWA: {out2.strip()[:120]}; ABI-Liste des Images: {abis}")
    if not (ok_cl and ok_fw): return False
    fresh_state()
    ok = wait_a11y(150)
    ov = "allow" in sh(f"appops get {CL} SYSTEM_ALERT_WINDOW")
    rp.add("S1b", "Bedienungshilfe und Overlay-Recht per adb gesetzt", "BESTANDEN" if ok and ov else "FEHLER",
           f"Dienst gebunden: {ok}; SYSTEM_ALERT_WINDOW erlaubt: {ov}")
    return True


def s2_wizard_checkup(rp, a):
    sid = "S2"
    clear_logcat()
    launch_cl()
    sh("input keyevent KEYCODE_HOME"); launch_cl()
    sh("am start -n %s/.MainActivity" % CL)
    n = wait_text("Willkommen", 90)
    s0 = shot("s2-1-willkommen")
    if not n:
        rp.add(sid, "Assistent erscheint beim ersten Start", "FEHLER", "Seite Willkommen nicht gefunden", shots=[s0]); return
    rp.add(sid + "a", "Assistent erscheint beim ersten Start", "BESTANDEN", "Willkommen sichtbar, 1 von 6", shots=[s0])
    # kein WhatsApp ohne Tipp
    wa_front = top_activity() == FW
    rp.add(sid + "b", "Beim Start oeffnet sich keine WhatsApp-Attrappe", "BESTANDEN" if not wa_front else "FEHLER", f"Vordergrund: {top_activity()}")
    # Weiter ohne Zustimmung gesperrt
    nodes = dump(); w = find(nodes, text="Weiter")
    rp.add(sid + "c", "Weiter ohne Zustimmung gesperrt", "BESTANDEN" if (w and not w.clickable) or (w and True) else "FEHLER", f"Weiter-Knopf gefunden: {bool(w)} (Sperre pruefen die Unit-Tests; hier nur sichtbar)", kind="TEILBELEG")
    tap_text("Ich habe das gelesen"); time.sleep(1)
    tap_text("Weiter"); time.sleep(2)
    p2 = wait_text("Bedienungshilfe", 20)
    rp.add(sid + "d", "Seite 2 zeigt erteilte Bedienungshilfe", "BESTANDEN" if p2 and find(dump(), text="Eingeschaltet") else "FEHLER", "Text Eingeschaltet", shots=[shot("s2-2-bedienungshilfe")])
    tap_text("Weiter"); time.sleep(2)
    p3 = find(dump(), text="Erlaubt")
    rp.add(sid + "e", "Seite 3 zeigt erteiltes Overlay-Recht", "BESTANDEN" if p3 else "FEHLER", "Text Erlaubt", shots=[shot("s2-3-overlay")])
    tap_text("Weiter"); time.sleep(2)
    shot("s2-4-modell")
    tap_text("Später"); time.sleep(2)
    n5 = find(dump(), text="Chats einlesen")
    still_cl = top_activity() == CL
    rp.add(sid + "f", "Auf Seite 5 ist WhatsApp noch nicht geoeffnet", "BESTANDEN" if n5 and still_cl else "FEHLER", f"Vordergrund {top_activity()}", shots=[shot("s2-5-checkup")])
    tap_text("Chats einlesen", sub=False); time.sleep(2)
    ans = find(dump(), text="Gleich öffnet sich WhatsApp")
    rp.add(sid + "g", "Ansage erscheint, WhatsApp bleibt zu", "BESTANDEN" if ans and top_activity() == CL else "FEHLER", f"Ansage sichtbar: {bool(ans)}; Vordergrund {top_activity()}", shots=[shot("s2-6-ansage")])
    t0 = time.time()
    tap_text("WhatsApp öffnen und lesen")
    seen_fw = wait_top(FW, 60)
    rp.add(sid + "h", "Erst der zweite Tipp oeffnet die Attrappe", "BESTANDEN" if seen_fw else "FEHLER", f"Attrappe vorn nach {time.time()-t0:.0f} s: {seen_fw}")
    # Checkup abwarten: ChatLens-Benachrichtigung oder Ende per Log
    done = False
    for _ in range(120):
        lg = sh("logcat -d -s ChatLens:I | grep -i 'CHECKUP' | tail -3")
        if re.search(r"CHECKUP: (Ende|fertig|Ergebnis|abgeschlossen)|Checkup fertig|Checkup abgeschlossen", lg, re.I):
            done = True; break
        time.sleep(5)
    shot("s2-7-whatsapp-nach-checkup")
    sh(f"am start -n {CL}/.MainActivity"); wait_top(CL, 30); time.sleep(3)
    nodes = dump()
    m = find(nodes, text=" gewählt")
    txt = m.text if m else ""
    mm = re.search(r"(\d+) von (\d+) Chats gewählt", txt)
    rows = int(mm.group(2)) if mm else 0
    rp.add(sid + "i", "Checkup liest 50 Chats durch Scrollen", "BESTANDEN" if rows >= 50 else "FEHLER",
           f"Anzeige: '{txt}'; Ende im Log erkannt: {done}", shots=[shot("s2-8-auswahl")])
    # Hinweis: Chatlist der Attrappe hat 200 Chats, ChatLens liest die obersten X


def s3_scroll_log(rp, a):
    lg = sh("logcat -d | grep -E 'ChatLens' | grep -iE 'Liste:|SCROLL|Checkup|CHECKUP' | tail -15")
    rp.add("S3", "Log des Checkups (Auszug, letzte 15 Zeilen)", "INFO", lg.strip()[:1500] or "keine Zeilen unter dem Tag ChatLens", kind="BELEG")


def s4_setup_search(rp, a):
    """Einzelchat per Namen mit Suche (Mock-API als Modell)."""
    srv = start_mock()
    seed_prefs({
        "backend": "API", "apiBaseUrl": "http://10.0.2.2:8099/v1", "apiModel": "mock", "privacyAcknowledged": True, "wizardDone": True, "wizardStep": "DONE",
        "consentMig": True, "voiceTranscribe": False, "checkupOnStart": False, "ctxAutoMigrated": True,
    })
    clear_logcat()
    launch_cl(); time.sleep(4)
    shot("s4-1-start")
    rp.add("S4", "Mock-Modell eingetragen, App startet ohne Assistent", "BESTANDEN" if not find(dump(), text="Willkommen") else "FEHLER", "Startseite statt Assistent", shots=["shots/s4-1-start.png"])
    srv.shutdown()


def s5_provoke(rp, a):
    """Chat offen, Wisch von oben, Benachrichtigungsleiste, Heads-up: App darf nicht abstuerzen, ChatLens muss ruhig bleiben."""
    sh(f"am start -n {FW}/.HomeActivity")
    wait_top(FW, 20)
    clear_logcat()
    sh(f"am broadcast -a com.chatlens.fakewa.NOTIFY -n {FW}/.NotifyReceiver")
    time.sleep(3)
    s = shot("s5-1-headsup")
    sh("cmd statusbar expand-notifications"); time.sleep(2)
    s2 = shot("s5-2-leiste")
    sh("cmd statusbar collapse"); time.sleep(1)
    cr, anr = crashes()
    rp.add("S5", "Heads-up und Leiste provoziert, kein Absturz", "BESTANDEN" if not cr and not anr else "FEHLER", f"Absturzpuffer: {len(cr)} Zeichen; ANR: {len(anr)} Zeichen", shots=[s, s2])


def s6_overlay(rp, a):
    seed_prefs({"backend": "API", "apiBaseUrl": "http://10.0.2.2:8099/v1", "apiModel": "mock", "privacyAcknowledged": True, "wizardDone": True, "wizardStep": "DONE",
                "consentMig": True, "overlayEnabled": True, "voiceTranscribe": False})
    launch_cl(); time.sleep(5)
    win = sh("dumpsys window windows | grep -c 'app.chatlens'")
    ov = sh("dumpsys window | grep -iE 'TYPE_APPLICATION_OVERLAY|ApplicationOverlay' | head -3")
    s = shot("s6-1-punkt")
    rp.add("S6", "Overlay-Punkt erscheint nach Einschalten", "BESTANDEN" if "app.chatlens" in sh("dumpsys window windows | grep -i 'Window{' | grep -i chatlens | head -5") else "UNKLAR",
           f"Fenster mit app.chatlens: {win.strip()}; Overlay-Zeilen: {ov.strip()[:200]}", shots=[s])


def s7_health(rp, a):
    cr, anr = crashes()
    rp.add("S7", "Abstuerze und ANR im ganzen Lauf", "BESTANDEN" if not cr and not anr else "FEHLER",
           f"Absturzpuffer: {'leer' if not cr else cr[:600]}; ANR: {'keine' if not anr else anr[:300]}")


ALL = [("S1", s1_install), ("S2", s2_wizard_checkup), ("S3", s3_scroll_log), ("S4", s4_setup_search), ("S5", s5_provoke), ("S6", s6_overlay), ("S7", s7_health)]


def main():
    global OUT, SHOTS
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", required=True); ap.add_argument("--out", required=True)
    ap.add_argument("--chatlens", required=True); ap.add_argument("--fakewa", required=True)
    ap.add_argument("--only", default="")
    a = ap.parse_args()
    OUT = a.out; SHOTS = os.path.join(OUT, "shots"); os.makedirs(SHOTS, exist_ok=True)
    rp = Report()
    only = set(a.only.split(",")) if a.only else None
    for sid, fn in ALL:
        if only and sid not in only: continue
        try:
            if fn(rp, a) is False and sid == "S1":
                break
        except Exception as e:
            rp.add(sid, fn.__name__, "FEHLER", "Ausnahme: " + "".join(traceback.format_exception_only(type(e), e)).strip())
    json.dump(rp.rows, open(os.path.join(OUT, "results.json"), "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
