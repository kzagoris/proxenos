#!/usr/bin/env python3
"""Fetch the GUI's icons and write them as Compose vector drawables (GUI-SPEC §6).

  python3 gui/icons.py

Material Symbols Outlined, weight 300, 24 px, from Google's icon CDN. Each SVG is one <path> in
the viewBox "0 -960 960 960"; a vector drawable's viewport starts at 0, so the path is moved down
960 by a group. Anything else is refused rather than converted wrongly.
"""
from pathlib import Path
import re
import urllib.request

ICONS = [
    "add", "arrow_back", "check_circle", "error", "extension", "folder", "help", "history", "hub",
    "link", "link_off", "power_settings_new", "sync", "terminal", "visibility", "visibility_off", "warning", "edit",
]
URL = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/{}/wght300/24px.svg"
OUT = Path(__file__).resolve().parent / "src/main/composeResources/drawable"

OUT.mkdir(parents=True, exist_ok=True)
for name in ICONS:
    svg = urllib.request.urlopen(URL.format(name)).read().decode()
    assert 'viewBox="0 -960 960 960"' in svg, f"{name}: not a Material Symbols viewBox"
    paths = re.findall(r'<path d="([^"]+)"', svg)
    assert len(paths) == 1, f"{name}: expected one path, found {len(paths)}"
    (OUT / f"{name}.xml").write_text(f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
  android:width="24dp" android:height="24dp"
  android:viewportWidth="960" android:viewportHeight="960">
  <group android:translateY="960">
    <path android:fillColor="#FF000000" android:pathData="{paths[0]}"/>
  </group>
</vector>
""")
