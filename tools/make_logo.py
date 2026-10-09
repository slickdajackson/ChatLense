#!/usr/bin/env python3
"""Erzeugt das Logo von ChatLens (Version 0.2.8, Entwurf 2a) als Android-Vector-Drawables.

  python3 tools/make_logo.py

Motiv: Sprechblase als Flaeche im Verlauf Tuerkis nach Violett, ein grosser und zwei kleine vierzackige KI-Sterne sind ausgespart
(Negativraum, evenOdd). Keine Buchstaben, keine Lupe. Vorlage der Zeichnung: tools/logo_drafts.py (Entwurf 2a).
Es entstehen in app/src/main/res:
  drawable/ic_logo_mark.xml      farbige Marke, 48 dp, fuer Overlay-Punkt und App-Kopfzeile
  drawable/ic_launcher_fg.xml    Adaptive-Icon-Vordergrund, 108 dp, Marke im Sicherheitsbereich (60 von 108 dp)
  drawable/ic_launcher_bg.xml    Adaptive-Icon-Hintergrund, 108 dp, Verlauf Indigo nach Nachtblau
  drawable/ic_launcher_mono.xml  einfarbige Fassung fuer Themed Icons (Android 13 und neuer)
  drawable/ic_stat.xml           Benachrichtigungssymbol, 24 dp, einfarbig
  mipmap-anydpi-v26/ic_launcher.xml
"""
import os

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
NS = 'xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"'
TEAL, VIOLET, WHITE = "#4DE3D0", "#8B7CFF", "#FFFFFF"


def sparkle(cx, cy, r, k=0.14):
    d = k * r
    return (f"M{cx:g},{cy - r:g}Q{cx + d:g},{cy - d:g} {cx + r:g},{cy:g}Q{cx + d:g},{cy + d:g} {cx:g},{cy + r:g}"
            f"Q{cx - d:g},{cy + d:g} {cx - r:g},{cy:g}Q{cx - d:g},{cy - d:g} {cx:g},{cy - r:g}z")


# Sprechblase: abgerundetes Rechteck 4..44 x 6..36, Schwanz unten links; Sterne: ein grosser, zwei kleine (im 48er Raster)
BUBBLE = "M13,6H35A9,9 0 0 1 44,15V27A9,9 0 0 1 35,36H20L10.5,43.5L12,36H13A9,9 0 0 1 4,27V15A9,9 0 0 1 13,6Z"
STAR_BIG = (19.5, 22.5, 10.0)
STAR_TOP = (33.0, 14.5, 4.8)
STAR_LOW = (32.5, 29.0, 3.6)
K = 0.16


def star(t):
    return sparkle(t[0], t[1], t[2], K)


def grad(attr, x1, y1, x2, y2, c1, c2):
    return (f'<aapt:attr name="android:{attr}"><gradient android:type="linear" android:startX="{x1}" android:startY="{y1}" '
            f'android:endX="{x2}" android:endY="{y2}"><item android:offset="0" android:color="{c1}"/><item android:offset="1" android:color="{c2}"/></gradient></aapt:attr>')


def fillpath(d, color=None, g=None, evenodd=False, alpha=None):
    a = ' android:fillType="evenOdd"' if evenodd else ""
    if g:
        return f'<path android:pathData="{d}"{a}>{g}</path>'
    al = f' android:fillAlpha="{alpha}"' if alpha is not None else ""
    return f'<path android:pathData="{d}" android:fillColor="{color}"{a}{al}/>'


def mark(mono=False):
    """Pfade der Marke im 48er Raster: Blase mit ausgesparten Sternen. mono=True: eine Farbe (weiss)."""
    d = BUBBLE + star(STAR_BIG) + star(STAR_TOP) + star(STAR_LOW)
    if mono:
        return fillpath(d, color=WHITE, evenodd=True)
    return fillpath(d, g=grad("fillColor", 4, 6, 44, 44, TEAL, VIOLET), evenodd=True)


def bg():
    g = ('<aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">'
         '<item android:offset="0" android:color="#2A1F6B"/><item android:offset="1" android:color="#0B1020"/></gradient></aapt:attr>')
    return f'<vector {NS}\n    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n    <path android:pathData="M0,0H108V108H0Z">{g}</path>\n</vector>\n'


def write(name, text, folder="drawable"):
    p = os.path.join(RES, folder, name)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    open(p, "w", encoding="utf-8").write(text)


def main():
    m = f'<vector {NS}\n    android:width="48dp" android:height="48dp" android:viewportWidth="48" android:viewportHeight="48">\n    {mark()}\n</vector>\n'
    grp = '<group android:translateX="24" android:translateY="24" android:scaleX="1.25" android:scaleY="1.25">\n    {}\n    </group>'
    head = f'<vector {NS}\n    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n    '
    write("ic_logo_mark.xml", m)
    write("ic_launcher_fg.xml", head + grp.format(mark()) + "\n</vector>\n")
    write("ic_launcher_mono.xml", head + grp.format(mark(True)) + "\n</vector>\n")
    write("ic_launcher_bg.xml", bg())
    write("ic_stat.xml", f'<vector {NS}\n    android:width="24dp" android:height="24dp" android:viewportWidth="48" android:viewportHeight="48">\n    {mark(True)}\n</vector>\n')
    write("ic_launcher.xml", '<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
          '    <background android:drawable="@drawable/ic_launcher_bg" />\n    <foreground android:drawable="@drawable/ic_launcher_fg" />\n'
          '    <monochrome android:drawable="@drawable/ic_launcher_mono" />\n</adaptive-icon>\n', folder="mipmap-anydpi-v26")
    print("Logo geschrieben (Entwurf 2a)")


main()
