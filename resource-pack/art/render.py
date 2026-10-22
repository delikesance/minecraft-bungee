#!/usr/bin/env python3
"""Paints the menu backgrounds and icons into PNG textures (developer tool).

Needs headless Google Chrome (or Chromium) and network access for Google Fonts.
The resulting PNGs are committed under ../pack/; the build (scripts/build_pack.py)
needs only the standard library.

    python3 resource-pack/art/render.py [name ...]
"""
import concurrent.futures, json, os, pathlib, shutil, subprocess, sys, tempfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import layout

HERE = pathlib.Path(__file__).resolve().parent
PACK = HERE.parent / "pack" / "assets" / "vampyuyu"
SCALE = layout.PX_PER_GUI / layout.CSS_PER_GUI  # css px -> texture px (4 texture px per GUI px)

CHROME = os.environ.get("CHROME") or next(
    (c for c in ("google-chrome", "chromium", "chromium-browser") if shutil.which(c)), None)

FONTS = ('<link href="https://fonts.googleapis.com/css2?family=Cinzel:wght@500;700&'
         'family=Pixelify+Sans:wght@400;600;700&display=swap" rel="stylesheet">')

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

def title(name=""):
    """Painted brand prefix; the plugin appends the dynamic part of the title at GUI x=44."""
    tail = f' <span style="color:#e9d99b">{name}</span>' if name else ""
    return ('<div style="position:absolute;left:24px;top:15px;font-size:20px;text-shadow:0 2px 6px rgba(5,5,30,.9)">'
            '<span style="color:#ff9aa0;font-weight:600">Vampyuyu</span> '
            f'<span style="color:#c5a69b">·</span>{tail}</div>')

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
{hills27()}{lower_panel27()}'''
    body = (title("Worlds")
            + text(21, 186, 162, "CREATIVE", 19, "#fff1e0", True, "center", 4)
            + text(21, 210, 162, "Build freely. Dream bigger.", 12, "#ffd7d2", extra="white-space:nowrap")
            + text(333, 186, 162, "SURVIVAL", 19, "#f2ffe8", True, "center", 4)
            + text(321, 210, 186, "Gather. Explore. Make it yours.", 12, "#d6f0c8", extra="white-space:nowrap")
            + text(183, 118, 150, "VAMPYUYU", 13, "#e9d99b", True, "center", 4)
            + text(183, 138, 150, "Choose your world", 13, "#d9d0ea", extra="white-space:nowrap")
            + text(183, 188, 150, "← Back to the lobby", 13, "#fff", extra="white-space:nowrap"))
    return W, H, frame(W, H, svg, body)

def grad(i, stops):
    return f'<linearGradient id="{i}" x1="0" y1="0" x2="0" y2="1">' + "".join(
        f'<stop offset="{o}" stop-color="{c}"/>' for o, c in stops) + "</linearGradient>"

def glass(x, y, w, h, r=14, op=.30):
    return (f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="#0a0820" fill-opacity="{op}" '
            'stroke="#fff" stroke-opacity=".28"/>')

def pines(xs, y, h, fill):
    return "".join(f'<path d="M{x} {y} l{h*.28:.0f} {h} h{-h*.56:.0f} z" fill="{fill}"/>'
                   f'<path d="M{x} {y+h*.35:.0f} l{h*.34:.0f} {h*.75:.0f} h{-h*.68:.0f} z" fill="{fill}"/>' for x in xs)

def village(top, bottom, seed=5):
    """Lit night village behind the inventory half, so the lower window is scenery, not a blank panel."""
    import random
    r = random.Random(seed)
    base = top + (bottom - top) * .62
    out = f'<rect x="0" y="{top}" width="528" height="{bottom - top}" fill="#1a1030"/>'
    out += f'<ellipse cx="264" cy="{base}" rx="300" ry="60" fill="#ffb454" fill-opacity=".10"/>'
    x = 6
    while x < 520:
        w, h = r.randint(40, 64), r.randint(44, 84)
        out += (f'<rect x="{x}" y="{base - h:.0f}" width="{w}" height="{h}" fill="#34205a"/>'
                f'<path d="M{x - 5} {base - h:.0f} l{w / 2 + 5:.0f} -26 l{w / 2 + 5:.0f} 26z" fill="#22143f"/>')
        for wx in range(x + 9, x + w - 12, 18):
            for wy in range(int(base - h + 16), int(base - 14), 24):
                if r.random() < .7:
                    out += f'<rect x="{wx}" y="{wy}" width="9" height="12" fill="#ffd27a" fill-opacity=".9"/>'
        x += w + r.randint(8, 20)
    out += f'<rect x="0" y="{base:.0f}" width="528" height="{bottom - base:.0f}" fill="#120a24"/>'
    for _ in range(14):
        out += (f'<circle cx="{r.randint(10, 518)}" cy="{r.randint(int(top + 20), int(bottom - 20))}" r="2" '
                'fill="#fff0a8" fill-opacity=".8"/>')
    return out

def lower_panel(claw=False):
    """Painted bottom half of the 54-slot window: the player-inventory area (GUI y 140-222).
    Faint slot outlines sit exactly under the vanilla slots (x=8+18c, y=140+18r, hotbar y=198)."""
    slots = "".join(
        f'<rect x="{24 + 54 * c}" y="{420 + 54 * r}" width="48" height="48" rx="7" fill="#0a0820" fill-opacity=".16" '
        'stroke="#e9d99b" stroke-opacity=".3" stroke-width="1.5"/>'
        for r in range(3) for c in range(9))
    hotbar = "".join(
        f'<rect x="{24 + 54 * c}" y="594" width="48" height="48" rx="7" fill="{"#c8453a" if claw and c == 8 else "#0a0820"}" fill-opacity="{.16}" '
        f'stroke="{"#f08a80" if claw and c == 8 else "#e9d99b"}" stroke-opacity="{.7 if claw and c == 8 else .22}" stroke-width="1.5"/>'
        for c in range(9))
    return (village(402, 666) + glass(15, 408, 498, 174, 16, .12) + slots + glass(15, 582, 498, 66, 16, .16) + hotbar)

def hills27():
    """Night hills and pines behind the inventory half of the 27-slot windows."""
    return village(236, 504, 9)

def lower_panel27():
    """Bottom half of the 27-slot window (176x168): inventory rows at GUI y=84/102/120, hotbar y=142."""
    slots = "".join(
        f'<rect x="{24 + 54 * c}" y="{252 + 54 * r}" width="48" height="48" rx="7" fill="#0a0820" fill-opacity=".16" '
        'stroke="#e9d99b" stroke-opacity=".3" stroke-width="1.5"/>'
        for r in range(3) for c in range(9))
    hotbar = "".join(
        f'<rect x="{24 + 54 * c}" y="426" width="48" height="48" rx="7" fill="#0a0820" fill-opacity=".16" '
        'stroke="#e9d99b" stroke-opacity=".3" stroke-width="1.5"/>' for c in range(9))
    return glass(15, 240, 498, 174, 16, .12) + slots + glass(15, 414, 498, 72, 16, .16) + hotbar

def setup():
    """/arena setup: 54-slot container (176x222). Rows y=51+54r; strips group the controls."""
    W, H = 528, 666
    svg = f"""<defs>{grad('sk', [(0, '#0b0e2e'), (.42, '#2b2260'), (.62, '#7a3a7a'), (.7, '#1a0f33'), (1, '#0c0720')])}
<radialGradient id="mg"><stop offset="0" stop-color="#fff6d8" stop-opacity=".8"/><stop offset="1" stop-color="#8aa0ff" stop-opacity="0"/></radialGradient>
<radialGradient id="gg"><stop offset="0" stop-color="#8fd16a" stop-opacity=".55"/><stop offset="1" stop-color="#8fd16a" stop-opacity="0"/></radialGradient>
<radialGradient id="rg"><stop offset="0" stop-color="#e6564a" stop-opacity=".55"/><stop offset="1" stop-color="#e6564a" stop-opacity="0"/></radialGradient></defs>
<rect width="{W}" height="{H}" fill="url(#sk)"/>{stars(330, 26, 5)}
<circle cx="484" cy="28" r="60" fill="url(#mg)"/><circle cx="484" cy="28" r="14" fill="#fff4d6"/>
<path d="M0 420 C110 380 200 430 300 410 S450 390 528 420 V{H} H0z" fill="#1b1442"/>
{pines([20, 70, 130, 200, 300, 380, 440, 500], 430, 80, '#0f0a2a')}
<path d="M0 520 C120 500 230 530 340 515 S480 505 528 525 V{H} H0z" fill="#0b0720"/>
{''.join(glass(21, 53 + 54 * r, 486, 50, 14, .55) for r in range(4))}
<circle cx="{21 + 7 * 54 + 27}" cy="78" r="30" fill="url(#gg)"/><circle cx="{21 + 8 * 54 + 27}" cy="78" r="30" fill="url(#rg)"/>
<circle cx="{21 + 8 * 54 + 27}" cy="{51 + 4 * 54 + 27}" r="30" fill="url(#rg)"/>{lower_panel()}""" 
    caps = ["ARENA", "VILLAGE", "LIMITS", "RULES"]
    body = title() + "".join(text(28, 51 + 54 * i + 18, 50, c, 11, "#e9d99b", True, "left", 1) for i, c in enumerate(caps))
    return W, H, frame(W, H, svg, body)

def listing():
    """Paged list (warps, arenas, houses, doors): entries rows 0-3, nav + create on row 5."""
    W, H = 528, 666
    svg = f"""<defs>{grad('sk', [(0, '#1d1244'), (.4, '#7a3f7e'), (.58, '#f0a07a'), (.64, '#2a1448'), (1, '#0c0720')])}
<radialGradient id="sg"><stop offset="0" stop-color="#fff0c8" stop-opacity=".9"/><stop offset="1" stop-color="#ffb454" stop-opacity="0"/></radialGradient></defs>
<rect width="{W}" height="{H}" fill="url(#sk)"/>{stars(180, 20, 9)}
<circle cx="264" cy="380" r="150" fill="url(#sg)"/>
<path d="M0 410 L70 340 L130 390 L210 310 L300 400 L380 330 L528 410 V{H} H0z" fill="#3a1f5a"/>
<path d="M0 470 L90 400 L170 460 L260 390 L350 470 L440 400 L528 460 V{H} H0z" fill="#241240"/>
<path d="M0 520 Q264 480 528 520 V{H} H0z" fill="#150a2c"/>
{glass(21, 51, 486, 219, 18)}
<rect x="171" y="326" width="186" height="42" rx="21" fill="#e9d99b" fill-opacity=".18" stroke="#e9d99b" stroke-opacity=".8" stroke-width="2"/>{lower_panel()}"""
    body = title() + text(171, 335, 186, "Create", 15, "#fff6d8")
    return W, H, frame(W, H, svg, body)

def detail():
    """Warp/house detail: 27 slots. Teleport portal on slot 13, side actions 11 and 15, back on 18."""
    W, H = 528, 504
    svg = f"""<defs>{grad('sk', [(0, '#0c2438'), (.3, '#2f6b7c'), (.5, '#f3c58c'), (.51, '#10303a'), (1, '#07161a')])}
<radialGradient id="pg"><stop offset="0" stop-color="#d8fff4" stop-opacity=".95"/><stop offset=".45" stop-color="#6fe0c4" stop-opacity=".5"/><stop offset="1" stop-color="#3fb59a" stop-opacity="0"/></radialGradient></defs>
<rect width="{W}" height="{H}" fill="url(#sk)"/>{stars(120, 16, 4)}
<path d="M0 230 L100 170 L190 215 L280 150 L380 215 L460 170 L528 230 V{H} H0z" fill="#10303a"/>
<circle cx="264" cy="132" r="96" fill="url(#pg)"/>
<circle cx="264" cy="132" r="62" fill="none" stroke="#d8fff4" stroke-width="3"/><circle cx="264" cy="132" r="50" fill="none" stroke="#fff" stroke-opacity=".35" stroke-dasharray="3 6"/>
<circle cx="156" cy="132" r="32" fill="#fff" fill-opacity=".1" stroke="#fff" stroke-opacity=".4"/>
<circle cx="372" cy="132" r="32" fill="#f08a80" fill-opacity=".14" stroke="#f08a80" stroke-opacity=".6"/>{hills27()}{lower_panel27()}"""
    body = title()
    return W, H, frame(W, H, svg, body)

def confirm():
    """Destructive confirmation: 27 slots. Cancel on 19-21, confirm on 23-25."""
    W, H = 528, 504
    svg = f"""<defs>{grad('sk', [(0, '#1a0a14'), (.3, '#5a1626'), (.5, '#d9604a'), (.51, '#1a0a14'), (1, '#0c0510')])}
<radialGradient id="eg"><stop offset="0" stop-color="#ffb070" stop-opacity=".7"/><stop offset="1" stop-color="#ff6a2c" stop-opacity="0"/></radialGradient></defs>
<rect width="{W}" height="{H}" fill="url(#sk)"/>{stars(110, 12, 8)}
<circle cx="264" cy="110" r="120" fill="url(#eg)"/>
<path d="M264 36 l44 76 h-88z" fill="none" stroke="#f0b26a" stroke-width="2.5" stroke-linejoin="round"/><path d="M264 62v26M264 98v2" stroke="#f0b26a" stroke-width="4" stroke-linecap="round"/>
<rect x="75" y="155" width="162" height="52" rx="18" fill="#fff" fill-opacity=".1" stroke="#fff" stroke-opacity=".55" stroke-width="2"/>
<rect x="291" y="155" width="162" height="52" rx="18" fill="#d6483c" fill-opacity=".35" stroke="#f08a80" stroke-width="2"/>{hills27()}{lower_panel27()}"""
    body = (title() + text(75, 159 + 14, 162, "CANCEL", 17, "#fff6d8", True, "center", 3).replace("top:173px", "top:170px")
            + text(291, 170, 162, "CONFIRM", 17, "#ffd6d0", True, "center", 3)
            + text(60, 124, 408, "This cannot be undone.", 13, "#ffe3c4"))
    return W, H, frame(W, H, svg, body)

ICONS={
'star':'<path d="M12 2l2.4 7.3 7.6.1-6.1 4.5 2.3 7.4L12 16.8 5.8 21.3l2.3-7.4L2 9.4l7.6-.1z" fill="#cfe4ff" fill-opacity=".55"/>',
'vial':'<path d="M9 3h6M10 3v6l-4 9a2 2 0 0 0 2 3h8a2 2 0 0 0 2-3l-4-9V3" /><path d="M8 15h8" stroke="#b78cff"/>',
'book':'<path d="M5 3h11l3 3v15H5z"/><path d="M8 8h8M8 12h8M8 16h5"/>',
'clock':'<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
'exit':'<path d="M6 3h10v18H6z"/><circle cx="13" cy="12" r="1" fill="#fff6d8"/><path d="M20 12h-4M18 10l2 2-2 2" stroke="#f08a80"/>',
'play':'<path d="M7 4l13 8-13 8z" fill="#8fd16a" fill-opacity=".7" stroke="#d6f2c2"/>',
'stop':'<rect x="5" y="5" width="14" height="14" rx="1" fill="#d6483c" fill-opacity=".7" stroke="#f6c3be"/>',
'back':'<path d="M20 12H5M11 6l-6 6 6 6"/>',
'next':'<path d="M4 12h15M13 6l6 6-6 6"/>',
'axe':'<path d="M4 20L15 9"/><path d="M12 4c4-1 7 1 8 4-3 1-6 0-8-1z" fill="#e9b23a" fill-opacity=".5"/>',
'map':'<path d="M3 6l6-2 6 2 6-2v14l-6 2-6-2-6 2z"/><path d="M9 4v14M15 6v14"/>',
'compass':'<circle cx="12" cy="12" r="9"/><path d="M15.5 8.5l-2 5-5 2 2-5z" fill="#c8453a"/>',
'bed':'<path d="M3 18V8M3 14h18v4M21 14v-3a3 3 0 0 0-3-3h-7v6"/><circle cx="7" cy="11" r="1.8" fill="#c8453a"/>',
'bone':'<path d="M7 17l10-10M5.5 15.5a2.2 2.2 0 1 0 1.5 3.7 2.2 2.2 0 1 0 3.7-1.5M18.5 8.5a2.2 2.2 0 1 0-1.5-3.7 2.2 2.2 0 1 0-3.7 1.5"/>',
'sun':'<circle cx="12" cy="12" r="4" fill="#e9b23a" fill-opacity=".6"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M5 5l2 2M17 17l2 2M19 5l-2 2M7 17l-2 2"/>',
'lever':'<rect x="3" y="15" width="18" height="5" rx="1"/><path d="M12 15l5-10"/><circle cx="17" cy="5" r="1.6" fill="#c8453a"/>',
'coin':'<circle cx="12" cy="12" r="9" fill="#e9b23a" fill-opacity=".4"/><path d="M12 7v10M9.5 9.5h4a1.5 1.5 0 0 1 0 3h-3a1.5 1.5 0 0 0 0 3h4"/>',
'door':'<path d="M6 3h12v18H6z"/><circle cx="15" cy="12" r="1.2" fill="#fff6d8"/>',
'pearl':'<circle cx="12" cy="12" r="8" fill="#3fb59a" fill-opacity=".45"/><path d="M8 9c1-2 3-3 5-3" stroke="#d8fff4"/>',
'plus':'<path d="M12 5v14M5 12h14"/>',
'pencil':'<path d="M4 20l1-5L16 4l4 4L9 19z"/><path d="M13 7l4 4"/>',
'trash':'<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13" stroke="#f08a80"/>',
'warn':'<path d="M12 3l10 18H2z" stroke="#f0b26a"/><path d="M12 10v5M12 18v.5" stroke="#f0b26a"/>',
'tag':'<path d="M4 4h8l8 8-8 8-8-8z"/><circle cx="8.5" cy="8.5" r="1.3" fill="#fff6d8"/>',
'ruler':'<path d="M3 17L17 3l4 4L7 21z"/><path d="M7 13l2 2M10 10l2 2M13 7l2 2"/>',
'sword':'<path d="M5 19L18 6V3h-3L2 16M7 13l4 4M4 20l3-3"/>',
'chest':'<rect x="3" y="9" width="18" height="11" rx="1"/><path d="M3 13h18M12 12v3"/><path d="M3 9a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4"/>',
'note':'<path d="M9 18V5l11-2v13"/><circle cx="6.5" cy="18" r="2.5" fill="#fff6d8"/><circle cx="17.5" cy="16" r="2.5" fill="#fff6d8"/>',
}

ICON_STROKE = 'stroke="#fff6d8" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" fill="none"'

def icon_html(name):
    return (f'<!doctype html><html style="background:transparent"><body style="margin:0;background:transparent">'
            f'<svg width="128" height="128" viewBox="-4 -4 32 32" {ICON_STROKE}>{ICONS[name]}</svg></body></html>')

def render_icons():
    """One transparent 128px PNG, item model and item definition per icon (item_model vampyuyu:icon/<name>)."""
    import json
    base = PACK
    for sub in ("textures/item/icon", "models/item/icon", "items/icon"):
        (base / sub).mkdir(parents=True, exist_ok=True)
    for name in ICONS:
        with tempfile.TemporaryDirectory() as tmp:
            page = pathlib.Path(tmp) / "i.html"
            page.write_text(icon_html(name), encoding="utf-8")
            subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-sandbox",
                            "--default-background-color=00000000", "--window-size=128,128",
                            f"--screenshot={base / 'textures/item/icon' / (name + '.png')}", page.as_uri()],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        (base / "models/item/icon" / f"{name}.json").write_text(json.dumps(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"vampyuyu:item/icon/{name}"}}, indent=2) + "\n")
        (base / "items/icon" / f"{name}.json").write_text(json.dumps(
            {"model": {"type": "minecraft:model", "model": f"vampyuyu:item/icon/{name}"}}, indent=2) + "\n")
    print("wrote", len(ICONS), "icons")

def compass():
    """In-game arena compass: heads on a council ring under a howling-wolf moon, controls on the bottom row."""
    W, H = 528, 666
    svg = f"""<defs>{grad('sk', [(0, '#080a24'), (.4, '#241c58'), (.58, '#5c3070'), (.66, '#120b2a'), (1, '#0a0618')])}
<radialGradient id="mg"><stop offset="0" stop-color="#fff6d8" stop-opacity=".8"/><stop offset="1" stop-color="#8aa0ff" stop-opacity="0"/></radialGradient>
<radialGradient id="fg"><stop offset="0" stop-color="#ffcf70" stop-opacity=".95"/><stop offset=".5" stop-color="#ff8a3c" stop-opacity=".35"/><stop offset="1" stop-color="#ff6a2c" stop-opacity="0"/></radialGradient></defs>
<rect width="{W}" height="{H}" fill="url(#sk)"/>{stars(300, 22, 3)}
<circle cx="484" cy="28" r="60" fill="url(#mg)"/><circle cx="484" cy="28" r="14" fill="#fff4d6"/>
<g fill="#140d33"><path d="M0 330 V220 h30 v-26 q14 -26 28 0 v26 h36 v-40 q16 -30 32 0 v40 h20 V330z"/><path d="M528 330 V206 h-34 v-30 q-16 -28 -32 0 v30 h-40 v-24 q-14 -24 -28 0 v24 h-18 V330z"/></g>
<ellipse cx="264" cy="186" rx="236" ry="132" fill="#0a0820" fill-opacity=".45" stroke="#e9d99b" stroke-opacity=".55" stroke-width="2"/>
<ellipse cx="264" cy="186" rx="226" ry="122" fill="none" stroke="#fff" stroke-opacity=".12" stroke-dasharray="3 7"/>
<circle cx="264" cy="170" r="96" fill="url(#mg)"/><circle cx="264" cy="170" r="58" fill="#fff4d6"/>
<g fill="#e3d3a4" fill-opacity=".55"><circle cx="246" cy="152" r="9"/><circle cx="280" cy="186" r="12"/><circle cx="268" cy="140" r="5"/></g>
<path d="M214 252 Q264 214 314 252 V262 H214z" fill="#0b0720"/>
<path d="M246 238 l4 -22 l-8 -10 l12 2 l8 -12 l6 12 l12 -2 l-8 10 l4 22 l-9 -6 h-12z" fill="#05030f"/>
<g fill="#05030f"><path d="M150 96 q-10 -10 -22 0 q10 2 14 10 q4 -8 8 -10z"/><path d="M400 120 q-10 -10 -22 0 q10 2 14 10 q4 -8 8 -10z"/><path d="M360 70 q-8 -8 -18 0 q8 2 11 8 q3 -6 7 -8z"/></g>
<g fill="#ffcf70"><circle cx="60" cy="300" r="5"/><circle cx="468" cy="300" r="5"/></g>
<circle cx="60" cy="300" r="26" fill="url(#fg)"/><circle cx="468" cy="300" r="26" fill="url(#fg)"/>
<g fill="#05030f"><rect x="57" y="302" width="6" height="26"/><rect x="465" y="302" width="6" height="26"/></g>
<g fill="#ffd27a" fill-opacity=".9"><rect x="30" y="236" width="8" height="10"/><rect x="48" y="236" width="8" height="10"/><rect x="108" y="226" width="8" height="10"/><rect x="420" y="222" width="8" height="10"/><rect x="474" y="236" width="8" height="10"/></g>
<path d="M0 470 Q132 440 264 465 T528 450 V{H} H0z" fill="#0b0720"/>{pines([30, 90, 160, 260, 350, 430, 490], 470, 90, '#080518')}
<path d="M0 560 Q132 535 264 555 T528 545 V{H} H0z" fill="#06040f"/>{lower_panel(True)}"""
    body = title()
    return W, H, frame(W, H, svg, body)

def alembic():
    """Witch potion phase: same grid as the compass, a glowing flask instead of the campfire."""
    W, H = 528, 666
    svg = f"""<defs>{grad('sk', [(0, '#0b1030'), (.4, '#2b1f5e'), (.58, '#4a2a78'), (.66, '#120b2a'), (1, '#0a0618')])}
<radialGradient id="mg"><stop offset="0" stop-color="#fff6d8" stop-opacity=".8"/><stop offset="1" stop-color="#8aa0ff" stop-opacity="0"/></radialGradient>
<radialGradient id="pg"><stop offset="0" stop-color="#b78cff" stop-opacity=".7"/><stop offset="1" stop-color="#8a5ad8" stop-opacity="0"/></radialGradient></defs>
<rect width="{W}" height="{H}" fill="url(#sk)"/>{stars(300, 22, 7)}
<circle cx="484" cy="28" r="60" fill="url(#mg)"/><circle cx="484" cy="28" r="14" fill="#fff4d6"/>
<ellipse cx="264" cy="186" rx="236" ry="132" fill="#0a0820" fill-opacity=".45" stroke="#b78cff" stroke-opacity=".6" stroke-width="2"/>
<circle cx="264" cy="190" r="70" fill="url(#pg)"/>
<path d="M240 150h48M246 150v36l-26 46a14 14 0 0 0 12 20h64a14 14 0 0 0 12-20l-26-46v-36" fill="none" stroke="#e9d4ff" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
<path d="M232 214h64l12 22a8 8 0 0 1-7 12h-74a8 8 0 0 1-7-12z" fill="#b78cff" fill-opacity=".55"/>
<circle cx="252" cy="200" r="4" fill="#e9d4ff"/><circle cx="276" cy="190" r="3" fill="#e9d4ff"/>
<path d="M0 470 Q132 440 264 465 T528 450 V{H} H0z" fill="#0b0720"/>{pines([30, 90, 160, 260, 350, 430, 490], 470, 90, '#080518')}
<path d="M0 560 Q132 535 264 555 T528 545 V{H} H0z" fill="#06040f"/>{lower_panel(True)}"""
    return W, H, frame(W, H, svg, title())

def profile():
    """/profil: balance medallion on slot 4, event history card on rows 1-4, paging on row 5."""
    W, H = 528, 666
    svg = f"""<defs>{grad('sk', [(0, '#2d1b4e'), (.3, '#8a4a7a'), (.5, '#f5c06a'), (.56, '#3a1f5a'), (1, '#120a24')])}
<radialGradient id="cg"><stop offset="0" stop-color="#ffe9a8" stop-opacity=".95"/><stop offset="1" stop-color="#ffb454" stop-opacity="0"/></radialGradient></defs>
<rect width="{W}" height="{H}" fill="url(#sk)"/>{stars(160, 22, 6)}
<circle cx="264" cy="78" r="70" fill="url(#cg)"/><circle cx="264" cy="78" r="30" fill="#e9b23a" fill-opacity=".35" stroke="#ffe9a8" stroke-width="2.5"/><circle cx="264" cy="78" r="24" fill="none" stroke="#fff" stroke-opacity=".4" stroke-dasharray="3 5"/>
{glass(21, 105, 486, 218, 16, .5)}
<path d="M0 450 Q264 400 528 450 V{H} H0z" fill="#3a1f5a"/><path d="M0 520 Q264 480 528 520 V{H} H0z" fill="#1a0e34"/>{lower_panel()}"""
    body = (title() + text(24, 66, 190, "EVENT HISTORY", 11, "#e9d99b", True, "left", 2)
            + text(318, 70, 190, "Global balance", 12, "#fff6d8", True, "left", 2))
    return W, H, frame(W, H, svg, body)

WEREWOLF = HERE / "werewolf"   # illustrations of the werewolf design guide (decors, icons, cards)

# Design guide colours (tokens.json of the "Loup-Garou Vampyuyu" design system)
GUI = {"face": "#c9cad6", "tab": "#b9bbca", "title": "#22223a", "outline": "#14121f", "light": "#ffffff",
       "shadow": "#7d7f95", "panel": "#2b3150", "panel-light": "#48507a", "panel-dark": "#1a1e33",
       "box": "#232842", "night-800": "#120b2a", "ink-muted": "#cdbfe3", "ink-dim": "#8f84b3"}

# name -> (decor, painted title, role colour, bottom boxes as (first column, width, label, tone))
ROLE_MENUS = {
    "menu_vote":    ("vote", "VILLAGE VOTE", "#ffd36e", [(0, 3, "YOUR VOTE", None), (3, 3, "LEADING", None), (6, 3, "SKIP", GUI["ink-dim"])]),
    "menu_hunt":    ("werewolf", "THE HUNT", "#ff5a5a", [(0, 4, "YOUR PREY", None), (5, 4, "THE PACK", None)]),
    "menu_vision":  ("seer", "THE VISION", "#b892ff", [(0, 9, "YOUR VISIONS", None)]),
    "menu_potions": ("witch", "THE POTIONS", "#6fdc8c", [(0, 3, "LIFE", "#ffd36e"), (3, 3, "DEATH", "#6fdc8c"), (6, 3, "SLEEP", GUI["ink-dim"])]),
    "menu_guard":   ("guard", "THE GUARD", "#6cc6ff", [(0, 4, "LAST NIGHT", None), (5, 4, "PROTECT ME", "#6cc6ff")]),
    "menu_lovers":  ("cupid", "THE LOVERS", "#ff8fc0", [(0, 5, "THE LOVERS", None), (6, 3, "BIND", "#ff8fc0")]),
    "menu_shot":    ("hunter", "LAST SHOT", "#f0a050", [(0, 5, "YOUR TARGET", None), (6, 3, "SHOOT", "#f0a050")]),
}
RING = [2, 3, 4, 5, 6, 16, 25, 34, 43, 51, 50, 49, 48, 47, 37, 28, 19, 10]  # plugin: WerewolfMenus.RING

def role_menu(name):
    """A werewolf role menu, drawn on the real 54-slot grid: the role's decor behind the chest, the
    18 ring slots for the heads, the labelled boxes of the bottom panel and the hotbar's card, timer
    and lock slots. Only the static frame is painted; heads, icons and counts are real items."""
    decor, label, color, boxes = ROLE_MENUS[name]
    W, H = 528, 666
    g = GUI
    bevel = "box-shadow:inset 3px 3px 0 rgba(255,255,255,.18),inset -3px -3px 0 rgba(0,0,0,.35);"
    def box(x, y, w, h, css):
        return f'<div style="position:absolute;box-sizing:border-box;left:{x}px;top:{y}px;width:{w}px;height:{h}px;{css}"></div>'
    def label_at(x, y, w, t, tone):
        return (f'<div style="position:absolute;left:{x}px;top:{y}px;width:{w}px;text-align:center;font-size:18px;'
                f'font-weight:700;letter-spacing:1px;color:{tone};text-shadow:2px 2px 0 rgba(0,0,0,.45)">{t}</div>')
    out = [box(0, 0, W, H, f"background:{g['face']};border:3px solid {g['outline']};"
                           f"box-shadow:inset 3px 3px 0 {g['light']},inset -3px -3px 0 {g['shadow']};")]
    out.append(f'<div style="position:absolute;box-sizing:border-box;left:114px;top:9px;width:300px;height:33px;'
               f'background:{g["tab"]};border:3px solid {g["outline"]};display:flex;align-items:center;justify-content:center;'
               f'font-size:18px;font-weight:600;letter-spacing:2px;color:{g["title"]}">{label}</div>')
    picture = (WEREWOLF / "decors" / f"{decor}.png").as_uri()
    out.append(f'<div style="position:absolute;box-sizing:border-box;left:15px;top:48px;width:498px;height:336px;'
               f'border:3px solid {g["outline"]};background:{g["night-800"]};overflow:hidden">'
               f'<img src="{picture}" style="position:absolute;left:0;top:0;width:492px;height:330px;object-fit:cover">'
               f'<div style="position:absolute;inset:0;box-shadow:inset 0 0 0 3px {color}"></div></div>')
    for slot in RING:
        x, y = 21 + 54 * (slot % 9), 51 + 54 * (slot // 9)
        out.append(box(x, y, 54, 54, f"border:3px solid {color};background:rgba(18,11,42,.72);{bevel}"))
    out.append(box(15, 396, 498, 258, f"border:3px solid {g['outline']};background:{g['panel']};"
                                      f"box-shadow:inset 3px 3px 0 {g['panel-light']},inset -3px -3px 0 {g['panel-dark']};"))
    for col, width, text, tone in boxes:
        x, y = 24 + 54 * col - 3, 420 - 3
        fill = f"color-mix(in srgb,{tone} 30%,{g['box']})" if tone else g["box"]
        out.append(box(x, y, width * 54, 108, f"border:3px solid {g['outline']};background:{fill};"
                                              f"box-shadow:inset 3px 3px 0 {tone or '#3d4570'},inset -3px -3px 0 rgba(0,0,0,.35);"))
        out.append(label_at(x, y + 11, width * 54, text, tone or g["ink-muted"]))
        for c in range(col, col + width):  # faint wells where the box's items sit (inventory row 1)
            out.append(box(24 + 54 * c, 474, 48, 48, "background:rgba(0,0,0,.22);"))
    for col in (0, 4, 8):
        out.append(box(24 + 54 * col - 3, 594 - 3, 54, 54, f"border:3px solid {g['outline']};background:{g['box']};{bevel}"))
    html = f'''<!doctype html><html><head><meta charset="utf-8">{FONTS}
<style>html,body{{margin:0;background:{g['night-800']}}}</style></head><body>
<div style="position:relative;width:{W}px;height:{H}px;overflow:hidden;font-family:'Pixelify Sans',monospace">{"".join(out)}</div></body></html>'''
    return W, H, html

def render_werewolf():
    """Werewolf items and HUD cards, from the guide's illustrations (needs Pillow, not Chrome):
    icons and role cards as item models (vampyuyu:icon/<name>), and the card glyphs of the
    vampyuyu:hud font. Icons are reduced with a quality filter, never upscaled."""
    from PIL import Image, ImageEnhance
    icon_dir = PACK / "textures" / "item" / "icon"
    def save_icon(name, image):
        image.save(icon_dir / f"{name}.png", optimize=True)
        (PACK / "models/item/icon" / f"{name}.json").write_text(json.dumps(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"vampyuyu:item/icon/{name}"}}, indent=2) + "\n")
        (PACK / "items/icon" / f"{name}.json").write_text(json.dumps(
            {"model": {"type": "minecraft:model", "model": f"vampyuyu:item/icon/{name}"}}, indent=2) + "\n")
    def grey(image):
        rgb = ImageEnhance.Brightness(image.convert("RGBA").convert("LA").convert("RGBA")).enhance(.6)
        rgb.putalpha(image.convert("RGBA").getchannel("A"))
        return rgb
    for sub in ("textures/item/icon", "models/item/icon", "items/icon", "textures/font/card", "font"):
        (PACK / sub).mkdir(parents=True, exist_ok=True)
    icons = {p.stem: Image.open(p).convert("RGBA").resize((32, 32), Image.LANCZOS)
             for p in sorted((WEREWOLF / "icons").glob("*.png"))}
    icons["timer"] = icons.pop("clock")  # "clock" already names the admin menus' line icon
    for name, image in icons.items():
        save_icon(name, image)
    save_icon("potion-life-off", grey(icons["potion-life"]))
    save_icon("potion-death-off", grey(icons["potion-death"]))
    save_icon("mark-last", grey(icons["shield"]))
    save_icon("skip", skip_icon())
    cards = {p.stem: Image.open(p).convert("RGBA") for p in sorted((WEREWOLF / "cards").glob("*.png"))}
    for role in layout.CARDS:
        save_icon(f"card-{role}", cards[role].resize((64, 64), Image.LANCZOS))
    save_icon("mark-wolf", grey(cards["werewolf"].resize((64, 64), Image.LANCZOS)))
    providers = []
    for i, role in enumerate(layout.CARDS):
        face = cards[role].resize((64, 64), Image.LANCZOS)
        face.save(PACK / "textures/font/card" / f"{role}.png", optimize=True)
        providers.append({"type": "bitmap", "file": f"vampyuyu:font/card/{role}.png", "ascent": layout.CARD_ASCENT,
                          "height": layout.CARD_HEIGHT, "chars": [chr(layout.CARD_BASE + i)]})
        if role != "back":
            grey(face).save(PACK / "textures/font/card" / f"{role}-dead.png", optimize=True)
            providers.append({"type": "bitmap", "file": f"vampyuyu:font/card/{role}-dead.png", "ascent": layout.CARD_ASCENT,
                              "height": layout.CARD_HEIGHT, "chars": [chr(layout.CARD_BASE + layout.CARD_DEAD + i)]})
    (PACK / "font" / "hud.json").write_text(json.dumps({"providers": providers}, indent=1) + "\n")
    print("wrote", len(icons) + 4 + len(layout.CARDS) + 1, "werewolf icons and", len(providers), "card glyphs")

def skip_icon():
    """Hand-drawn 16x16 pixel ballot struck through, shown 2x: the SKIP button of the vote."""
    from PIL import Image
    rows = ["................",
            "..oooooooooooo..",
            "..oppppppppppo..",
            "..oppppppppprro.",
            "..opppppppprrro.",
            "..oplllllprrrpo.",
            "..oppppprrrpppo.",
            "..opllrrrllllpo.",
            "..oppprrrppppo..",
            "..oprrrllllppo..",
            "..orrrpppppppo..",
            ".orrrlllllllpo..",
            ".orrppppppppppo.",
            "..oooooooooooo..",
            "................",
            "................"]
    palette = {"o": (20, 18, 31, 255), "p": (246, 234, 208, 255), "l": (143, 132, 179, 255),
               "r": (255, 90, 90, 255), ".": (0, 0, 0, 0)}
    image = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row[:16]):
            image.putpixel((x, y), palette[ch])
    return image.resize((32, 32), Image.NEAREST)

ART = {"menu_lobby": lobby, "menu_setup": setup, "menu_list": listing, "menu_detail": detail, "menu_confirm": confirm, "menu_compass": compass, "menu_profile": profile, "menu_alembic": alembic,
       **{name: (lambda n=name: role_menu(n)) for name in ROLE_MENUS}}

def _shot(html, w, h, out):
    with tempfile.TemporaryDirectory() as tmp:
        page = pathlib.Path(tmp) / "art.html"
        page.write_text(html, encoding="utf-8")
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--no-sandbox",
                        f"--force-device-scale-factor={SCALE}", f"--window-size={w},{h}",
                        "--virtual-time-budget=8000", f"--screenshot={out}", page.as_uri()],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def render(name):
    """Cuts one background into the tiles listed in layout.BACKGROUNDS and rewrites the font.
    The page is shot once, with spare window height (headless Chrome may reserve some of it), then
    cropped to the window and cut into tiles."""
    from PIL import Image
    if not CHROME:
        sys.exit("Chrome/Chromium is required to render art")
    w, h, html = ART[name]()
    base, gui_h, rows = layout.BACKGROUNDS[name]
    assert (w, h) == (layout.GUI_W * layout.CSS_PER_GUI, gui_h * layout.CSS_PER_GUI), (name, w, h)
    tile_w, tile_h = layout.TILE_W * layout.PX_PER_GUI, layout.tile_height(gui_h, rows) * layout.PX_PER_GUI
    outdir = PACK / "textures" / "font"
    outdir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        shot = pathlib.Path(tmp) / "full.png"
        _shot(html, w, h + 300, shot)
        full = Image.open(shot).convert("RGBA").crop((0, 0, layout.GUI_W * layout.PX_PER_GUI, gui_h * layout.PX_PER_GUI))
    for r in range(rows):
        for c in range(layout.COLS):
            full.crop((c * tile_w, r * tile_h, (c + 1) * tile_w, (r + 1) * tile_h)).save(
                outdir / f"{layout.tile_file(name, r, c)}.png", optimize=True)
    print("wrote", rows * layout.COLS, "tiles for", name)

def write_font():
    providers = [{"type": "space", "advances": {chr(k): v for k, v in layout.ADVANCES.items()}}]
    for name, (base, gui_h, rows) in layout.BACKGROUNDS.items():
        th = layout.tile_height(gui_h, rows)
        for r in range(rows):
            for c in range(layout.COLS):
                providers.append({"type": "bitmap", "file": f"vampyuyu:font/{layout.tile_file(name, r, c)}.png",
                                  "ascent": layout.ASCENT - r * th, "height": th, "chars": [chr(base + r * layout.COLS + c)]})
    (PACK / "font").mkdir(parents=True, exist_ok=True)
    (PACK / "font" / "menus.json").write_text(json.dumps({"providers": providers}, indent=1) + "\n")
    print("wrote font with", len(providers) - 1, "tiles")

if __name__ == "__main__":
    wanted = sys.argv[1:] or [*ART, "icons", "werewolf"]
    for n in wanted:
        render_icons() if n == "icons" else render_werewolf() if n == "werewolf" else render(n)
    write_font()
