#!/usr/bin/env python3
"""Paints the menu backgrounds and icons into PNG textures (developer tool).

Needs headless Google Chrome (or Chromium) and network access for Google Fonts.
The resulting PNGs are committed under ../pack/; the build (scripts/build_pack.py)
needs only the standard library.

    python3 resource-pack/art/render.py [name ...]
"""
import os, pathlib, shutil, subprocess, sys, tempfile

HERE = pathlib.Path(__file__).resolve().parent
PACK = HERE.parent / "pack" / "assets" / "vampyuyu"
SCALE = 1.5  # 528 css px -> 792 px texture; the font provider scales it to 176 GUI px

CHROME = next((c for c in ("google-chrome", "chromium", "chromium-browser") if shutil.which(c)), None)

FONTS = ('<link href="https://fonts.googleapis.com/css2?family=Cinzel:wght@500;700&'
         'family=Pixelify+Sans:wght@400;600&display=swap" rel="stylesheet">')

def stars(h, n, seed):
    import random
    r = random.Random(seed)
    return "".join(
        f'<circle cx="{r.randint(8, 520)}" cy="{r.randint(6, h)}" r="{r.choice([1, 1.1, 1.3, 1.5])}" '
        f'fill="#fff" opacity="{r.choice([.4, .6, .8, .9])}"/>' for _ in range(n))

def text(x, y, w, t, size=14, color="#fff", serif=False, align="center", ls=0, extra=""):
    ff = "font-family:'Cinzel',serif;font-weight:700;" if serif else ""
    return (f'<div style="position:absolute;left:{x}px;top:{y}px;width:{w}px;text-align:{align};{ff}'
            f'font-size:{size}px;letter-spacing:{ls}px;color:{color};text-shadow:0 2px 6px rgba(5,5,30,.95);{extra}">{t}</div>')

def title(name):
    return ('<div style="position:absolute;left:24px;top:15px;font-size:20px;text-shadow:0 2px 6px rgba(5,5,30,.9)">'
            '<span style="color:#ff9aa0;font-weight:600">Vampyuyu</span> '
            f'<span style="color:#c5a69b">·</span> <span style="color:#e9d99b">{name}</span></div>')

def frame(w, h, svg, body):
    """Square-cornered full-bleed GUI texture (the container bitmap sits over the vanilla panel)."""
    return f'''<!doctype html><html><head><meta charset="utf-8">{FONTS}
<style>html,body{{margin:0;background:#120b2a}}</style></head><body>
<div style="position:relative;width:{w}px;height:{h}px;overflow:hidden;font-family:'Pixelify Sans',monospace;background:#120b2a">
<svg style="position:absolute;left:0;top:0" width="{w}" height="{h}" viewBox="0 0 {w} {h}">{svg}
<rect x="1.5" y="1.5" width="{w-3}" height="{h-3}" fill="none" stroke="#14121f" stroke-width="3"/>
<rect x="4.5" y="4.5" width="{w-9}" height="{h-9}" fill="none" stroke="#fff" stroke-opacity=".28" stroke-width="1.5"/></svg>
{body}</div></body></html>'''

def lobby():
    """/menu: 27-slot container, 176x168 GUI px. Slots: rows y=51+54r, cols x=21+54c."""
    W, H = 528, 504
    svg = f'''<defs>
<linearGradient id="dusk" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#150e2e"/><stop offset=".3" stop-color="#46246a"/><stop offset=".5" stop-color="#8a4a7a"/><stop offset=".7" stop-color="#1b1038"/><stop offset="1" stop-color="#0c0720"/></linearGradient>
<linearGradient id="cs" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#7a4fa8"/><stop offset=".6" stop-color="#ee9a7e"/><stop offset="1" stop-color="#ffd6a0"/></linearGradient>
<linearGradient id="ss" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0f2638"/><stop offset=".65" stop-color="#2f6b5c"/><stop offset="1" stop-color="#8ccf8a"/></linearGradient>
<radialGradient id="gl"><stop offset="0" stop-color="#ffe9b0" stop-opacity=".9"/><stop offset="1" stop-color="#ffb454" stop-opacity="0"/></radialGradient>
<radialGradient id="st"><stop offset="0" stop-color="#e7f1ff" stop-opacity=".95"/><stop offset=".4" stop-color="#a9c4ff" stop-opacity=".35"/><stop offset="1" stop-color="#7a8cff" stop-opacity="0"/></radialGradient>
<clipPath id="cc"><path d="M21 183 V132 a81 81 0 0 1 162 0 V183z"/></clipPath>
<clipPath id="sc"><path d="M345 183 V132 a81 81 0 0 1 162 0 V183z" transform="translate(-12 0)"/></clipPath></defs>
<rect width="{W}" height="{H}" fill="url(#dusk)"/>{stars(150, 18, 3)}
<g clip-path="url(#cc)"><rect x="21" y="51" width="162" height="132" fill="url(#cs)"/>
<circle cx="102" cy="140" r="40" fill="#fff0c8" opacity=".75"/>
<g fill="#4a2468"><rect x="30" y="150" width="26" height="33"/><rect x="58" y="136" width="22" height="47"/><rect x="84" y="160" width="30" height="23"/><rect x="116" y="128" width="24" height="55"/><rect x="142" y="148" width="30" height="35"/></g>
<g fill="#ffd27a"><rect x="36" y="160" width="5" height="6"/><rect x="45" y="172" width="5" height="6"/><rect x="64" y="148" width="5" height="6"/><rect x="64" y="164" width="5" height="6"/><rect x="92" y="170" width="5" height="6"/><rect x="122" y="140" width="5" height="6"/><rect x="122" y="160" width="5" height="6"/><rect x="150" y="160" width="5" height="6"/></g>
<g fill="#fff" opacity=".7"><rect x="52" y="104" width="10" height="10" transform="rotate(18 57 109)"/><rect x="128" y="96" width="8" height="8" transform="rotate(-14 132 100)"/><rect x="92" y="82" width="7" height="7"/></g></g>
<path d="M21 183 V132 a81 81 0 0 1 162 0 V183" fill="none" stroke="#ffd7a8" stroke-width="2.5"/><path d="M27 183 V132 a75 75 0 0 1 150 0 V183" fill="none" stroke="#fff" stroke-opacity=".25"/>
<g clip-path="url(#sc)"><rect x="333" y="51" width="162" height="132" fill="url(#ss)"/>
<circle cx="446" cy="86" r="12" fill="#f2f4d8" opacity=".9"/>
<path d="M333 183 V147 L370 107 L398 140 L428 95 L495 155 V183z" fill="#10303a"/>
<path d="M333 183 V165 L360 147 L388 167 L420 140 L495 172 V183z" fill="#0b2227"/>
<g fill="#07161a"><path d="M352 183 l10 -30 l10 30z"/><path d="M372 183 l12 -38 l12 38z"/><path d="M468 183 l10 -32 l10 32z"/></g>
<circle cx="426" cy="175" r="20" fill="url(#gl)"/><path d="M420 182 q6 -18 12 0z" fill="#ffb454"/><path d="M423 182 q3 -11 6 0z" fill="#fff0a8"/></g>
<path d="M333 183 V132 a81 81 0 0 1 162 0 V183" fill="none" stroke="#bfe6b0" stroke-width="2.5"/><path d="M339 183 V132 a75 75 0 0 1 150 0 V183" fill="none" stroke="#fff" stroke-opacity=".25"/>
<circle cx="258" cy="78" r="46" fill="url(#st)"/>
<path d="M258 49l7 21 22 .3-17.6 13 6.6 21-18-12.7-18 12.7 6.6-21L229 70.3l22-.3z" fill="#f2f8ff" stroke="#cfe4ff" stroke-width="1.5" stroke-linejoin="round"/>
'''
    body = (title("Worlds")
            + text(21, 186, 162, "CREATIVE", 19, "#fff1e0", True, "center", 4)
            + text(21, 210, 162, "Build freely. Dream bigger.", 12, "#ffd7d2", extra="white-space:nowrap")
            + text(333, 186, 162, "SURVIVAL", 19, "#f2ffe8", True, "center", 4)
            + text(321, 210, 186, "Gather. Explore. Make it yours.", 12, "#d6f0c8", extra="white-space:nowrap")
            + text(183, 118, 150, "VAMPYUYU", 13, "#e9d99b", True, "center", 4)
            + text(183, 138, 150, "Choose your world", 13, "#d9d0ea", extra="white-space:nowrap")
            + text(183, 188, 150, "← Back to the lobby", 13, "#fff", extra="white-space:nowrap"))
    return W, H, frame(W, H, svg, body)

ART = {"menu_lobby": lobby}

def render(name):
    if not CHROME:
        sys.exit("Chrome/Chromium is required to render art")
    w, h, html = ART[name]()
    out = PACK / "textures" / "font" / f"{name}.png"
    with tempfile.TemporaryDirectory() as tmp:
        page = pathlib.Path(tmp) / "art.html"
        page.write_text(html, encoding="utf-8")
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-sandbox",
                        f"--force-device-scale-factor={SCALE}", f"--window-size={w},{h}",
                        "--virtual-time-budget=8000", f"--screenshot={out}", page.as_uri()],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    print("wrote", out.relative_to(HERE.parent.parent))

if __name__ == "__main__":
    for n in (sys.argv[1:] or ART):
        render(n)
