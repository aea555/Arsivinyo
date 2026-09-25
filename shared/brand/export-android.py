#!/usr/bin/env python3
"""Draw the Android app's icons from icon.svg.

Expo takes PNGs for the launcher, so this renders them; nothing under
mobile/assets/images that it writes is edited by hand. Change icon.svg, run this again.

    python3 shared/brand/export-android.py      (needs resvg: brew install resvg)
"""
import pathlib
import re
import subprocess
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
OUT = HERE.parent.parent / "mobile" / "assets" / "images"

master = (HERE / "icon.svg").read_text()
defs = re.search(r"<defs>.*?</defs>", master, re.S).group(0)
ground = '<rect width="1024" height="1024" rx="230" fill="url(#ground)"/>'
# Everything drawn on top of the ground: the record and the sleeve.
art = master.split(ground, 1)[1].rsplit("</svg>", 1)[0]
sleeve = re.search(r'<clipPath id="sleeve-shape"><path d="([^"]+)"/>', master).group(1)

# The artwork spans x 196-828 and y 120-868, so its middle is (512, 494).
def placed(scale):
    return f'<g transform="translate(512 512) scale({scale}) translate(-512 -494)">{art}</g>'

def svg(body, extra_defs=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">'
            f"{defs}{extra_defs}{body}</svg>")

square_ground = '<rect width="1024" height="1024" fill="url(#ground)"/>'

# An adaptive icon is 108 dp, and only the middle 66 dp circle is sure to survive the
# launcher's mask. The corners of the sleeve are the furthest point, so the scale is set by
# them: 0.62 puts them inside that circle.
ADAPTIVE = 0.62

# The monochrome layer is one colour, which the launcher tints. The label becomes a hole
# with the arrow standing in it, and a gap separates the record from the sleeve.
mono = f"""
  <defs><mask id="record-cut">
    <rect width="1024" height="1024" fill="#fff"/>
    <circle cx="512" cy="400" r="98" fill="#000"/>
    <path d="{sleeve}" fill="#000" stroke="#000" stroke-width="44" stroke-linejoin="round"/>
  </mask></defs>
  <g transform="translate(512 512) scale({ADAPTIVE}) translate(-512 -494)">
    <circle cx="512" cy="400" r="280" fill="#fff" mask="url(#record-cut)"/>
    <path fill="none" stroke="#fff" stroke-width="22" stroke-linecap="round" stroke-linejoin="round"
          d="M512 350 V444 M474 408 L512 446 L550 408"/>
    <path d="{sleeve}" fill="#fff"/>
  </g>"""

images = {
    # name: (svg, pixels)
    "ic_launcher_background.png": (svg(square_ground), 432),
    "ic_launcher_foreground.png": (svg(placed(ADAPTIVE)), 432),
    "ic_launcher_monochrome.png": (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">{mono}</svg>', 432),
    # Google Play masks the corners itself, so its image is a full square.
    "play_store_512.png": (svg(square_ground + placed(0.78)), 512),
    # The splash screen draws this on black, so it is the artwork alone.
    "ic_launcher.png": (svg(placed(0.96)), 512),
    "favicon.png": (master, 96),
}

with tempfile.TemporaryDirectory() as tmp:
    for name, (source, size) in images.items():
        path = pathlib.Path(tmp) / (name + ".svg")
        path.write_text(source)
        subprocess.run(["resvg", "-w", str(size), "-h", str(size), str(path), str(OUT / name)], check=True)
        print(f"{name}  {size}px")
