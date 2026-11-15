#!/usr/bin/env python3
"""Generates the mod's pixel-art GUI sprites (vanilla style: light body + 1px dark drop shadow).

Run: python3 tools/icons.py   -> writes src/main/resources/assets/smartshots/textures/gui/sprites/*.png
"""
import math
import os
import struct
import sys
import zlib

OUT = os.path.join(os.path.dirname(__file__), "..", "src/main/resources/assets/smartshots/textures/gui/sprites")

PAL = {
    "W": (0xFC, 0xFC, 0xFC), "L": (0xD0, 0xD0, 0xD0), "G": (0xA8, 0xA8, 0xA8), "M": (0x6C, 0x6C, 0x6C),
    "D": (0x2A, 0x2A, 0x2A), "K": (0x14, 0x14, 0x14),
    "B": (0x3C, 0x51, 0xCB), "b": (0x83, 0x9F, 0xF5), "N": (0x25, 0x2B, 0xA6), "c": (0xDC, 0xE3, 0xFF),
    "Y": (0xF2, 0xC1, 0x4E), "y": (0xFF, 0xE8, 0x9A), "O": (0xC0, 0x83, 0x1C),
    "R": (0xE2, 0x2B, 0x3D), "r": (0xFF, 0x8A, 0x96), "Q": (0x93, 0x16, 0x26),
    "P": (0xB6, 0x9C, 0xFF), "p": (0xDA, 0xCC, 0xFF), "V": (0x7A, 0x5C, 0xD6),
    "C": (0x7F, 0xD1, 0xFF), "s": (0xC8, 0xEC, 0xFF), "S": (0x3A, 0x8E, 0xC4),
    "F": (0xE8, 0xB5, 0x4A), "f": (0xA8, 0x6E, 0x1A), "e": (0xFF, 0xD8, 0x7A),
    "g": (0x6C, 0xD9, 0x86), "h": (0x2E, 0x8B, 0x4A),
    "T": (0xE0, 0x9A, 0x3C), "t": (0x9E, 0x5A, 0x22),
}
SHADOW = (0x2A, 0x2A, 0x2A, 0xFF)


def png(path, w, h, px):
    raw = b"".join(b"\x00" + b"".join(struct.pack("4B", *px[y][x]) for x in range(w)) for y in range(h))

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    data = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)) \
        + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(data)


def canvas(w, h):
    return [[None] * w for _ in range(h)]


def grid(rows, w, h, ox=0, oy=0, c=None):
    c = c or canvas(w, h)
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != ".":
                c[oy + y][ox + x] = ch
    return c


def finish(name, c, shadow=True):
    h = len(c)
    w = len(c[0])
    px = [[(0, 0, 0, 0)] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            if c[y][x]:
                px[y][x] = PAL[c[y][x]] + (0xFF,)
    if shadow:
        for y in range(h):
            for x in range(w):
                if c[y][x] is None:
                    left = x > 0 and c[y][x - 1] is not None
                    up = y > 0 and c[y - 1][x] is not None
                    if left or up:
                        px[y][x] = SHADOW
    png(os.path.join(OUT, name + ".png"), w, h, px)


def check(name, rows, w):
    for i, r in enumerate(rows):
        if len(r) != w:
            sys.exit(f"{name}: row {i} has length {len(r)} != {w}: {r!r}")


def icon(name, rows, size=15, ox=None, oy=None, shadow=True):
    w = max(len(r) for r in rows)
    check(name, rows, w)
    ox = (size - 1 - w) // 2 + ((size - 1 - w) % 2) if ox is None else ox
    oy = (size - 1 - len(rows)) // 2 + ((size - 1 - len(rows)) % 2) if oy is None else oy
    finish(name, grid(rows, size, size, ox, oy), shadow)


def proc(name, size, fn, shadow=True):
    c = canvas(size, size)
    for y in range(size):
        for x in range(size):
            c[y][x] = fn(x, y)
    finish(name, c, shadow)


os.makedirs(OUT, exist_ok=True)

# ---------------------------------------------------------------- 15x15 button icons

icon("camera", [
    "....WWWWW.....",
    "...WLLLLLW....",
    "WWWWWWWWWWWWWW",
    "WLLWWMMMMWWRrW",
    "WWWWMNBBNMWWWW",
    "WWWMNBccBNMWWW",
    "WWWMBBcBBBMWWW",
    "WWWMBBBBBNMWWW",
    "WWWWMNNNNMWWWW",
    "WLLLLMMMMLLLLW",
    "WWWWWWWWWWWWWW",
])

icon("folder", [
    ".fffff........",
    "fFFFFFf.......",
    "fFFFFFFffffff.",
    "feeeeeeeeeeeef",
    "fFFFFFFFFFFFFf",
    "fFFFFFFFFFFFFf",
    "fFFFFFFFFFFFFf",
    "fFFFFFFFFFFFFf",
    "fFFFFFFFFFFFFf",
    "fFFFFFFFFFFFFf",
    "ffffffffffffff",
])

icon("trash", [
    "....WWWWW....",
    "....W...W....",
    "WWWWWWWWWWWWW",
    "LLLLLLLLLLLLL",
    ".WWWWWWWWWWW.",
    ".WWMWWMWWMWW.",
    ".WWMWWMWWMWW.",
    ".WWMWWMWWMWW.",
    ".WWMWWMWWMWW.",
    ".WWMWWMWWMWW.",
    ".WWWWWWWWWWW.",
    "..LLLLLLLLL..",
])

icon("note", [
    "..........RR.",
    ".........RrRQ",
    "........LYRQ.",
    ".......YyYL..",
    "......YyYO...",
    ".....YyYO....",
    "....YyYO.....",
    "...YyYO......",
    "..LYYO.......",
    "..LLO........",
    "..M..........",
])


def close_fn(x, y):
    if x > 12 or y > 12:
        return None
    if abs(x - y) <= 1 or abs(x + y - 12) <= 1:
        if (x, y) in ((0, 1), (1, 0), (11, 0), (12, 1), (0, 11), (1, 12), (11, 12), (12, 11)):
            return None
        return "W"
    return None


proc("close", 15, lambda x, y: close_fn(x - 1, y - 1) if x >= 1 and y >= 1 else None)


def gear_fn(x, y):
    cx = cy = 6.5
    dx = x - cx
    dy = y - cy
    r = math.hypot(dx, dy)
    a = math.atan2(dy, dx)
    if r <= 1.9:
        return None
    if r <= 4.3:
        return "L" if r <= 2.9 else "W"
    if r <= 6.6:
        tooth = (math.cos(a * 8) > 0.35)
        return "W" if tooth else None
    return None


proc("settings", 15, lambda x, y: gear_fn(x, y) if x < 14 and y < 14 else None)


def info_fn(x, y):
    cx = cy = 6
    r = math.hypot(x - cx, y - cy)
    if r > 6.4:
        return None
    letter = (x in (6,) and (y == 2 or 4 <= y <= 10)) or (y == 10 and x in (5, 7)) or (y == 4 and x == 5)
    if letter:
        return "W"
    return "N" if r > 5.5 else "B"


proc("info", 15, lambda x, y: info_fn(x - 1, y - 1) if x >= 1 and y >= 1 else None)


def external_fn(x, y):
    # box with a gap in the top-right corner
    box = (x in (0,) and 3 <= y <= 12) or (y == 12 and 0 <= x <= 9) or (x == 9 and 7 <= y <= 12) or (y == 3 and 0 <= x <= 4)
    head = (y in (0,) and 6 <= x <= 12) or (x == 12 and 0 <= y <= 6)
    shaft = abs((x - 4) - (8 - y)) <= 0 and 4 <= x <= 12 and 0 <= y <= 8
    shaft2 = abs((x - 5) - (8 - y)) <= 0 and 5 <= x <= 12 and 0 <= y <= 8
    if box:
        return "L"
    if head or shaft or shaft2:
        return "W"
    return None


proc("open", 15, lambda x, y: external_fn(x - 1, y - 1) if x >= 1 and y >= 1 else None)


def star_poly(cx, cy, R, r):
    pts = []
    for i in range(10):
        ang = -math.pi / 2 + i * math.pi / 5
        rad = R if i % 2 == 0 else r
        pts.append((cx + rad * math.cos(ang), cy + rad * math.sin(ang)))
    return pts


def inside(poly, x, y):
    n = len(poly)
    c = False
    j = n - 1
    for i in range(n):
        xi, yi = poly[i]
        xj, yj = poly[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            c = not c
        j = i
    return c


def star_fn(size, filled, R, r):
    poly = star_poly(size / 2 - 0.5, size / 2 + 0.2, R, r)
    inner = star_poly(size / 2 - 0.5, size / 2 + 0.2, R - 1.6, r - 0.9)

    def f(x, y):
        px, py = x + 0.5, y + 0.5
        if not inside(poly, px - 0.5 + 0.5, py):
            return None
        if filled:
            if py < size / 2 - 0.5 and px < size / 2:
                return "y"
            return "Y" if inside(inner, px, py) else "O"
        return None if inside(inner, px, py) else "W"

    return f


STAR = [
    "......Y......",
    ".....YyY.....",
    ".....YyY.....",
    "....YyYYY....",
    "YYYYYyYYYYYYO",
    ".YyyYYYYYYYO.",
    "..YYYYYYYYO..",
    "...YYYYYYO...",
    "...YYYYYYO...",
    "..YYYYOYYYO..",
    "..YYYO.OYYO..",
    ".YYO.....OYO.",
    ".YO.......OO.",
]
icon("star", STAR)


def outline(rows):
    h = len(rows)
    w = len(rows[0])
    out = []
    for y in range(h):
        line = ""
        for x in range(w):
            if rows[y][x] == ".":
                line += "."
                continue
            edge = False
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if nx < 0 or ny < 0 or nx >= w or ny >= h or rows[ny][nx] == ".":
                    edge = True
            line += "W" if edge else "."
        out.append(line)
    return out


icon("star_empty", outline(STAR))


def chevron(dirx, size_w, size_h, thick=2.2):
    def f(x, y):
        cy = (size_h - 1) / 2
        t = abs(y - cy)
        # line from tip to ends
        if dirx > 0:
            target = (size_w - 2) - t
        else:
            target = 1 + t
        return "W" if abs(x - target) < thick / 2 + 0.3 and t <= cy else None
    return f


def make_chev(name, dirx, w, h, color="W"):
    c = canvas(w + 1, h + 1)
    f = chevron(dirx, w, h)
    for y in range(h):
        for x in range(w):
            if f(x, y):
                c[y][x] = color
    finish(name, c)


make_chev("arrow_left", -1, 9, 15)
make_chev("arrow_right", 1, 9, 15)

# ---------------------------------------------------------------- 9x9 badges / small icons

icon("badge_trophy", [
    "YYYYYYYY",
    "OyYYYYOO",
    "OyYYYYO.",
    ".yYYYYO.",
    "..YYOO..",
    "...YO...",
    "..YYYO..",
    ".OOOOOO.",
], size=9, ox=0, oy=0)

icon("badge_clock", [
    "..WWWW..",
    ".WLLLLW.",
    "WLLMLLLW",
    "WLLMLLLW",
    "WLLMMMLW",
    "WLLLLLLW",
    ".WLLLLW.",
    "..WWWW..",
], size=9, ox=0, oy=0)

icon("badge_player", [
    "..CCCC..",
    "..CsCC..",
    "..CCCC..",
    "...SS...",
    ".CCCCCC.",
    "CCsCCCCC",
    "CCCCCCCC",
    "CC....CC",
], size=9, ox=0, oy=0)

icon("badge_star", [
    "...yO...",
    "..YyYO..",
    "YYyYYYYO",
    ".YYYYYO.",
    "..YYYYO.",
    ".YYYOYYO",
    ".YO...YO",
], size=9, ox=0, oy=0)

icon("all", [
    "WWW.WWW.",
    "WLW.WLW.",
    "WWW.WWW.",
    "........",
    "WWW.WWW.",
    "WLW.WLW.",
    "WWW.WWW.",
], size=9, ox=0, oy=0)

icon("expand_right", [
    "W...",
    "WW..",
    "WWW.",
    "WW..",
    "W...",
], size=7, ox=1, oy=0)

icon("expand_down", [
    "WWWWW",
    ".WWW.",
    "..W..",
], size=7, ox=0, oy=1)

icon("here", [
    "..gg..",
    ".gggg.",
    "gggggg",
    "gggggg",
    ".gggg.",
    "..gg..",
], size=7, ox=0, oy=0)

# ---------------------------------------------------------------- minimal UI line icons (white, tinted in code)

UI = os.path.join(OUT, "ui")
os.makedirs(UI, exist_ok=True)


def ui(name, rows):
    w = max(len(r) for r in rows)
    check(name, rows, w)
    c = grid(rows, w, len(rows))
    h = len(rows)
    px = [[(255, 255, 255, 255) if c[y][x] else (0, 0, 0, 0) for x in range(w)] for y in range(h)]
    png(os.path.join(UI, name + ".png"), w, h, px)


ui("settings", [
    "..WWW......",
    "WWW.WWWWWWW",
    "..WWW......",
    "...........",
    "......WWW..",
    "WWWWWWW.WWW",
    "......WWW..",
    "...........",
    "...WWW.....",
    "WWWW.WWWWWW",
    "...WWW.....",
])
ui("folder", [
    "WWWW.......",
    "W...W......",
    "W....WWWWWW",
    "W.........W",
    "W.........W",
    "W.........W",
    "W.........W",
    "W.........W",
    "WWWWWWWWWWW",
])
ui("close", [
    "W.......W",
    ".W.....W.",
    "..W...W..",
    "...W.W...",
    "....W....",
    "...W.W...",
    "..W...W..",
    ".W.....W.",
    "W.......W",
])
ui("info", [
    "...WWWWW...",
    ".WW.....WW.",
    ".W...W...W.",
    "W.........W",
    "W....W....W",
    "W....W....W",
    "W....W....W",
    "W....W....W",
    ".W.......W.",
    ".WW.....WW.",
    "...WWWWW...",
])
ui("trash", [
    "....WWW....",
    "WWWWWWWWWWW",
    ".W.......W.",
    ".W.W.W.W.W.",
    ".W.W.W.W.W.",
    ".W.W.W.W.W.",
    ".W.W.W.W.W.",
    ".W.......W.",
    "..WWWWWWW..",
])
ui("open", [
    "......WWWWW",
    ".........WW",
    "WWWW....W.W",
    "W......W..W",
    "W.....W...W",
    "W....W.....",
    "W...W...W..",
    "W.......W..",
    "W.......W..",
    "W.......W..",
    "WWWWWWWWW..",
])
STAR11 = [
    ".....W.....",
    "....WWW....",
    "....WWW....",
    "WWWWWWWWWWW",
    ".WWWWWWWWW.",
    "..WWWWWWW..",
    "...WWWWW...",
    "..WWWWWWW..",
    "..WWW.WWW..",
    ".WWW...WWW.",
    ".WW.....WW.",
]
ui("star", STAR11)
ui("star_empty", outline(STAR11))
ui("note", [
    "........WW.",
    ".......W..W",
    "......W..W.",
    ".....W..W..",
    "....W..W...",
    "...W..W....",
    "..W..W.....",
    ".W..W......",
    ".WWW.......",
    "WW.........",
])
ui("arrow_left", [
    ".....W",
    "....W.",
    "...W..",
    "..W...",
    ".W....",
    "W.....",
    ".W....",
    "..W...",
    "...W..",
    "....W.",
    ".....W",
])
ui("arrow_right", [
    "W.....",
    ".W....",
    "..W...",
    "...W..",
    "....W.",
    ".....W",
    "....W.",
    "...W..",
    "..W...",
    ".W....",
    "W.....",
])
ui("expand_right", [
    "W..",
    ".W.",
    "..W",
    ".W.",
    "W..",
])
ui("expand_down", [
    "W...W",
    ".W.W.",
    "..W..",
])
ui("clock", [
    "..WWWWW..",
    ".W.....W.",
    "W...W...W",
    "W...W...W",
    "W...WW..W",
    "W.......W",
    "W.......W",
    ".W.....W.",
    "..WWWWW..",
])
ui("freecam", [
    "WWWWWW...",
    "W....W..W",
    "W....W.WW",
    "W....WWWW",
    "W....W.WW",
    "W....W..W",
    "WWWWWW...",
])
ui("trophy", [
    "WWWWWWWWW",
    "W.W...W.W",
    "W.W...W.W",
    ".WW...WW.",
    "..W...W..",
    "...W.W...",
    "....W....",
    "....W....",
    "..WWWWW..",
])
ui("person", [
    "...WWW...",
    "..W...W..",
    "..W...W..",
    "...WWW...",
    ".........",
    "..WWWWW..",
    ".W.....W.",
    "W.......W",
    "WWWWWWWWW",
])
ui("all", [
    "WWWW.WWWW",
    "W..W.W..W",
    "W..W.W..W",
    "WWWW.WWWW",
    ".........",
    "WWWW.WWWW",
    "W..W.W..W",
    "W..W.W..W",
    "WWWW.WWWW",
])
ui("star_small", [
    "....W....",
    "...WWW...",
    "WWWWWWWWW",
    ".WWWWWWW.",
    "..WWWWW..",
    "..WWWWW..",
    ".WWW.WWW.",
    ".WW...WW.",
])
ui("dot", [
    ".WWW.",
    "WWWWW",
    "WWWWW",
    "WWWWW",
    ".WWW.",
])
ui("search", [
    "..WWW....",
    ".W...W...",
    "W.....W..",
    "W.....W..",
    "W.....W..",
    ".W...W...",
    "..WWW.W..",
    ".......W.",
    "........W",
])
ui("check", [
    "........W",
    ".......W.",
    "W.....W..",
    ".W...W...",
    "..W.W....",
    "...W.....",
])
ui("sort", [
    "..W...",
    ".WWW..",
    "W.W.W.",
    "..W...",
    "..W...",
    "..W...",
    "..W...",
    "..W...",
])
ui("copy", [
    "..WWWWWWW",
    "..W.....W",
    "WWWWWWW.W",
    "W.....W.W",
    "W.....W.W",
    "W.....W.W",
    "W.....WWW",
    "W.....W..",
    "WWWWWWW..",
])

# ---------------------------------------------------------------- editor & sharing (9x9)
ui('edit', [
    '......WW.',
    '.....W..W',
    '....W..W.',
    '...W..W..',
    '..W..W...',
    '.W..W....',
    'WW.W.....',
    'WWW......',
    'WW.......',
])
ui('share', [
    '....W....',
    '...WWW...',
    '..W.W.W..',
    '....W....',
    '....W....',
    'W...W...W',
    'W.......W',
    'W.......W',
    'WWWWWWWWW',
])
ui('undo', [
    '..W......',
    '.WW......',
    'WWWWWWW..',
    '.WW....W.',
    '..W.....W',
    '........W',
    '........W',
    '.......W.',
    '..WWWWW..',
])
ui('redo', [
    '......W..',
    '......WW.',
    '..WWWWWWW',
    '.W....WW.',
    'W.....W..',
    'W........',
    'W........',
    '.W.......',
    '..WWWWW..',
])
ui('tool_move', [
    '....W....',
    '...WWW...',
    '....W....',
    '.W..W..W.',
    'WWWWWWWWW',
    '.W..W..W.',
    '....W....',
    '...WWW...',
    '....W....',
])
ui('tool_pen', [
    '.......WW',
    '......W.W',
    '.....W.W.',
    '....W.W..',
    '...W.W...',
    '..W.W....',
    '.WWW.....',
    '.WW......',
    'W........',
])
ui('tool_marker', [
    '......WWW',
    '.....WWWW',
    '....WWWW.',
    '...WWWW..',
    '..WWWW...',
    '.W.WW....',
    'W.W......',
    'WW.......',
    'WWWWWWWWW',
])
ui('tool_line', [
    '........W',
    '.......W.',
    '......W..',
    '.....W...',
    '....W....',
    '...W.....',
    '..W......',
    '.W.......',
    'W........',
])
ui('tool_arrow', [
    '.........',
    '....WWWWW',
    '......WWW',
    '.....W.WW',
    '....W...W',
    '...W.....',
    '..W......',
    '.W.......',
    'W........',
])
ui('tool_rect', [
    'WWWWWWWWW',
    'W.......W',
    'W.......W',
    'W.......W',
    'W.......W',
    'W.......W',
    'W.......W',
    'W.......W',
    'WWWWWWWWW',
])
ui('tool_ellipse', [
    '..WWWWW..',
    '.W.....W.',
    'W.......W',
    'W.......W',
    'W.......W',
    'W.......W',
    'W.......W',
    '.W.....W.',
    '..WWWWW..',
])
ui('tool_text', [
    'WWWWWWWWW',
    'W...W...W',
    '....W....',
    '....W....',
    '....W....',
    '....W....',
    '....W....',
    '....W....',
    '...WWW...',
])
ui('tool_counter', [
    '..WWWWW..',
    '.WWWW.WW.',
    'WWWW..WWW',
    'WWWWW.WWW',
    'WWWWW.WWW',
    'WWWWW.WWW',
    'WWWW...WW',
    '.WWWWWWW.',
    '..WWWWW..',
])
ui('tool_blur', [
    '...W.W...',
    '..W.W.W..',
    '.W.W.W.W.',
    'W.W.W.W.W',
    '.W.W.W.W.',
    'W.W.W.W.W',
    '.W.W.W.W.',
    '..W.W.W..',
    '...W.W...',
])
ui('tool_pixelate', [
    'WWW...WWW',
    'WWW...WWW',
    'WWW...WWW',
    '...WWW...',
    '...WWW...',
    '...WWW...',
    'WWW...WWW',
    'WWW...WWW',
    'WWW...WWW',
])
ui('tool_spotlight', [
    'WWWWWWWWW',
    'WWWWWWWWW',
    'WW.....WW',
    'WW.....WW',
    'WW.....WW',
    'WW.....WW',
    'WW.....WW',
    'WWWWWWWWW',
    'WWWWWWWWW',
])
ui('tool_crop', [
    '..W......',
    '..W......',
    'WWWWWWW..',
    '..W...W..',
    '..W...W..',
    '..W...W..',
    '..WWWWWWW',
    '......W..',
    '......W..',
])
ui('tool_eraser', [
    '....WWW..',
    '...WWWWW.',
    '..WWWWWWW',
    '.W.WWWWW.',
    'W...WWW..',
    '.W...W...',
    '..W.W....',
    '...W.....',
    '.....WWWW',
])
ui('tool_sticker', [
    '..WWWWW..',
    '.W.....W.',
    'W.W...W.W',
    'W.......W',
    'W.......W',
    'W.W...W.W',
    'W..WWW..W',
    '.W.....W.',
    '..WWWWW..',
])
ui('tool_caption', [
    'WWWWWWWWW',
    'W.......W',
    'W.......W',
    'W.......W',
    'W.......W',
    'W..WWWWWW',
    'W..W....W',
    'W..WWWWWW',
    'WWWWWWWWW',
])
ui('tool_filter', [
    '....W....',
    '..W.W.W..',
    '.........',
    'W..WWW..W',
    '..WWWWW..',
    'W..WWW..W',
    '.........',
    '..W.W.W..',
    '....W....',
])
ui('map', [
    'W..W..W..',
    'WWW.WW.WW',
    'W.W.W.W.W',
    'W.W.W.W.W',
    'W.W.W.W.W',
    'W.W.W.W.W',
    'W.W.W.W.W',
    'WW.WW.WWW',
    '..W..W..W',
])

print("ok")
