#!/usr/bin/env python3
"""Checks or updates app/src/main/assets/model-catalog.json against the Hugging Face API.

  python3 tools/verify_catalog.py --check   compares size, SHA-256, gated status, license, and revision (exit code 1 on a mismatch)
  python3 tools/verify_catalog.py --fetch   writes revision, size, and SHA-256 from the API into the catalog file

Read-only requests to huggingface.co, no token. Gated repos do not provide a checksum; sha256 stays empty there.
"""
import json, sys, urllib.request, urllib.error

import os
PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "assets", "model-catalog.json")


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "chatlens-catalog-check"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def head(url):
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "chatlens-catalog-check", "Range": "bytes=0-0"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "--check"
    cat = json.load(open(PATH, encoding="utf-8"))
    bad = 0
    for m in cat["models"]:
        repo = m.get("repo")
        if not repo or m.get("source") == "github":
            continue
        info = get("https://huggingface.co/api/models/" + repo)
        gated = bool(info.get("gated"))
        lic = [t.split(":", 1)[1] for t in info.get("tags", []) if t.startswith("license:")]
        tree = get("https://huggingface.co/api/models/%s/tree/main" % repo)
        files = {f["path"]: f for f in tree if f["type"] == "file"}
        if m.get("files"):
            # multi-file model (speech recognition): check each file against the API
            rev = info.get("sha", "")
            diffs = []
            total = 0
            for mf in m["files"]:
                f = files.get(mf["name"])
                if f is None:
                    diffs.append("Datei fehlt: " + mf["name"]); continue
                total += f["size"]
                lfs = (f.get("lfs") or {}).get("oid", "")
                if mf["sizeBytes"] != f["size"]: diffs.append("%s Groesse %s != %s" % (mf["name"], mf["sizeBytes"], f["size"]))
                if lfs and lfs != mf["sha256"]: diffs.append("%s SHA-256 abweichend" % mf["name"])
                if not lfs:
                    # small file without an LFS hash: hash the revision URL itself
                    import hashlib
                    url = "https://huggingface.co/%s/resolve/%s/%s" % (repo, m.get("revision") or "main", mf["name"])
                    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "chatlens-catalog-check"}), timeout=60) as r:
                        h = hashlib.sha256(r.read()).hexdigest()
                    if h != mf["sha256"]: diffs.append("%s SHA-256 (selbst berechnet) abweichend" % mf["name"])
                if mode == "--fetch":
                    mf["sizeBytes"] = f["size"]
                    if lfs: mf["sha256"] = lfs
            if mode == "--fetch":
                m["sizeBytes"] = total; m["revision"] = rev; m["gated"] = gated
                print("OK ", m["id"], total, rev[:8], len(m["files"]), "Dateien")
                continue
            if m.get("sizeBytes") != total: diffs.append("Gesamtgroesse %s != %s" % (m.get("sizeBytes"), total))
            if m.get("revision") != rev: diffs.append("Revision neuer (Katalog %s, aktuell %s)" % (m.get("revision", "")[:8], rev[:8]))
            if m.get("licenseApi"):
                # the ONNX conversion often has no license tag; then the origin model counts (licenseOriginRepo)
                olic = lic
                if m.get("licenseOriginRepo"):
                    oi = get("https://huggingface.co/api/models/" + m["licenseOriginRepo"])
                    olic = [t.split(":", 1)[1] for t in oi.get("tags", []) if t.startswith("license:")]
                    if oi.get("gated"): diffs.append("Ursprungsmodell ist gated")
                if m["licenseApi"] not in olic: diffs.append("license %s is not in %s" % (m["licenseApi"], olic))
            print(("ABWEICHUNG " if diffs else "OK         ") + m["id"] + (": " + "; ".join(diffs) if diffs else ""))
            bad += 1 if diffs else 0
            continue
        if m.get("file"):
            f = files.get(m["file"])
            if f is None:
                print("FEHLT", m["id"], m["file"]); bad += 1; continue
            size = f["size"]
            sha = (f.get("lfs") or {}).get("oid", "")
            if set(sha) == {"*"}:
                sha = ""
            rev = info.get("sha", "")
        else:
            size = sum(f["size"] for f in files.values() if f["path"].endswith(".onnx") or f["path"] == "tokens.txt")
            sha = ""
            rev = info.get("sha", "")
        if mode == "--fetch":
            m["sizeBytes"] = size
            m["sha256"] = sha
            m["revision"] = rev
            m["gated"] = gated
            print("OK ", m["id"], size, sha[:12], rev[:8], "gated" if gated else "")
        else:
            diffs = []
            if m.get("sizeBytes") != size: diffs.append("Groesse %s != %s" % (m.get("sizeBytes"), size))
            if m.get("sha256", "") != sha: diffs.append("SHA-256 abweichend")
            if bool(m.get("gated")) != gated: diffs.append("gated %s != %s" % (m.get("gated"), gated))
            if m.get("licenseApi") and m["licenseApi"] not in lic: diffs.append("license %s is not in %s" % (m["licenseApi"], lic))
            if m.get("downloadable") and not gated and m.get("file"):
                code = head("https://huggingface.co/%s/resolve/%s/%s" % (repo, m.get("revision") or "main", m["file"]))
                if code not in (200, 206, 302):
                    diffs.append("Download-URL liefert HTTP %s" % code)
            if m.get("downloadable"):
                if len(m.get("sha256", "")) != 64 or any(ch not in "0123456789abcdef" for ch in m.get("sha256", "")):
                    diffs.append("SHA-256 in the catalog is not 64 hex characters")
                if len(m.get("revision", "")) != 40:
                    diffs.append("Revision im Katalog ist kein voller 40-stelliger Commit")
            if m.get("comparison") and (not m.get("downloadable") or m.get("gated")):
                diffs.append("Vergleichskandidat muss frei herunterladbar sein")
            if m.get("revision") and m["revision"] != rev:
                diffs.append("Revision neuer (Katalog %s, aktuell %s), Datei kann sich geaendert haben" % (m["revision"][:8], rev[:8]))
            print(("ABWEICHUNG " if diffs else "OK         ") + m["id"] + (": " + "; ".join(diffs) if diffs else ""))
            bad += 1 if diffs else 0
    if mode != "--fetch":
        std = [m["id"] for m in cat["models"] if m.get("status") == "standard"]
        cmpr = [m["id"] for m in cat["models"] if m.get("comparison")]
        if std != ["gemma-4-e4b"]:
            print("ABWEICHUNG Standardmodell:", std); bad += 1
        if len(cmpr) > 1:
            print("ABWEICHUNG mehrere Vergleichskandidaten:", cmpr); bad += 1
    if mode == "--fetch":
        json.dump(cat, open(PATH, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
        open(PATH, "a").write("\n")
    sys.exit(1 if bad else 0)


main()
