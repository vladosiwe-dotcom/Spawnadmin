"""Generates the 16x16 item textures: the Horn of the Heavens and the Seal of Banishment.

Usage: python3 tools/make_textures.py <textures/item dir>
"""
import os
import sys

from PIL import Image

# Hand-drawn pixel map. Legend:
#  . transparent   o outline   i ivory   l ivory light   s ivory shade
#  g gold          G gold light   b band blue   w white glow   c cyan glow
HORN = [
    "..........oooo..",
    "........ooGwwGo.",
    ".......oGwcccwgo",
    "......oGwcwwcwgo",
    "......oGwcccwggo",
    ".....olgGwwwGgo.",
    ".....oliggggoo..",
    "....olliisso....",
    "....olliisso....",
    "....obbbbbo.....",
    "....obccbbo.....",
    "....olliso......",
    ".....oliso......",
    "..oo.olso.......",
    ".oGgoliso.......",
    "..oogooo........",
]

PALETTE = {
    ".": (0, 0, 0, 0),
    "o": (38, 33, 52, 255),
    "i": (233, 226, 204, 255),
    "l": (252, 249, 236, 255),
    "s": (181, 168, 142, 255),
    "g": (214, 166, 58, 255),
    "G": (255, 222, 120, 255),
    "b": (62, 120, 210, 255),
    "w": (250, 253, 255, 255),
    "c": (150, 225, 255, 255),
}


def render(rows):
    img = Image.new("RGBA", (16, 16))
    for y, row in enumerate(rows):
        assert len(row) == 16, (y, row)
        for x, ch in enumerate(row):
            img.putpixel((x, y), PALETTE[ch])
    return img


def seal():
    """Seal of Banishment: a golden seal disc with a pale-blue star sigil and two blue ribbons."""
    img = Image.new("RGBA", (16, 16))
    outline = PALETTE["o"]
    cx, cy, r = 7.5, 6.5, 6.3
    # ribbons first, under the disc
    for y in range(10, 16):
        for x, dx in ((5, -1), (10, 1)):
            px = x + (y - 10) * dx // 2
            for k in (0, 1):
                xx = px + k
                if 0 <= xx < 16:
                    img.putpixel((xx, y), PALETTE["b"] if k == 0 else (40, 86, 170, 255))
            for xx in (px - 1, px + 2):
                if 0 <= xx < 16 and img.getpixel((xx, y))[3] == 0:
                    img.putpixel((xx, y), outline)
    for y in range(16):
        for x in range(16):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if d <= r - 0.9:
                light = (x - cx) + (y - cy) < -3
                shade = (x - cx) + (y - cy) > 4
                c = PALETTE["G"] if light else ((176, 128, 40, 255) if shade else PALETTE["g"])
                if abs(d - 3.9) < 0.5:
                    c = (150, 104, 30, 255)  # inner ring pressed into the gold
                img.putpixel((x, y), c)
            elif d <= r:
                img.putpixel((x, y), outline)
    # the sigil: a four-pointed star of light
    for (x, y), ch in {(7, 3): "c", (8, 3): "c", (7, 4): "w", (8, 4): "w", (4, 6): "c", (5, 6): "w", (6, 6): "w",
                       (7, 6): "w", (8, 6): "w", (9, 6): "w", (10, 6): "w", (11, 6): "c", (7, 5): "w", (8, 5): "w",
                       (7, 7): "w", (8, 7): "w", (7, 8): "w", (8, 8): "w", (7, 9): "c", (8, 9): "c",
                       (6, 5): "c", (9, 5): "c", (6, 7): "c", (9, 7): "c"}.items():
        img.putpixel((x, y), PALETTE[ch])
    return img


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    render(HORN).save(os.path.join(out, "heaven_horn.png"))
    seal().save(os.path.join(out, "banishing_seal.png"))
    print("written", os.path.join(out, "heaven_horn.png"), os.path.join(out, "banishing_seal.png"))


if __name__ == "__main__":
    main()
