#!/usr/bin/env python3
"""Draw the mod icon (src/main/resources/assets/tally/icon.png): a flight path marker
inside a target box, in HUD green. Needs Pillow."""
import os
from PIL import Image, ImageDraw

S = 512  # drawn large, scaled down for smooth edges
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources", "assets", "tally", "icon.png")
BG = (12, 18, 16, 255)
GREEN = (53, 255, 107, 255)

img = Image.new("RGBA", (S, S), BG)
d = ImageDraw.Draw(img)
w = 18

# horizon, banked
d.line([(40, 330), (200, 290)], fill=(53, 255, 107, 120), width=10)
d.line([(312, 262), (472, 222)], fill=(53, 255, 107, 120), width=10)

# target box corners
x0, y0, x1, y1, c = 96, 96, 416, 416, 86
for (ax, ay, sx, sy) in [(x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)]:
    d.line([(ax, ay), (ax + sx * c, ay)], fill=GREEN, width=w)
    d.line([(ax, ay), (ax, ay + sy * c)], fill=GREEN, width=w)

# flight path marker
cx, cy, r = 256, 256, 54
d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=GREEN, width=w)
d.line([(cx - r, cy), (cx - r - 78, cy)], fill=GREEN, width=w)
d.line([(cx + r, cy), (cx + r + 78, cy)], fill=GREEN, width=w)
d.line([(cx, cy - r), (cx, cy - r - 52)], fill=GREEN, width=w)

img.resize((128, 128), Image.LANCZOS).save(OUT)
print("wrote", OUT)
