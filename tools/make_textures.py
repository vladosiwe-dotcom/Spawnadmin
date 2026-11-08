"""Generates the 16x16 item texture for the Horn of the Heavens.

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


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    render(HORN).save(os.path.join(out, "heaven_horn.png"))
    print("written", os.path.join(out, "heaven_horn.png"))


if __name__ == "__main__":
    main()
