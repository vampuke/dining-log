#!/usr/bin/env python3
"""Measure actual P7 tokens, validate vectors and preserve every existing UI tag."""
from pathlib import Path
import json
import re
import subprocess
import hashlib
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'android/app/src/main/res'
FEATURE = ROOT / 'android/feature/src/main'
A = '{http://schemas.android.com/apk/res/android}'


def luminance(color):
    values = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in values]
    return sum(v * w for v, w in zip(linear, (.2126, .7152, .0722)))


def contrast(foreground, background):
    a, b = luminance(foreground), luminance(background)
    return (max(a, b) + .05) / (min(a, b) + .05)


source = (FEATURE / 'kotlin/top/alanvampuke/dininglog/feature/theme/Colors.kt').read_text()
measurements = {}
for theme in ('Light', 'Dark'):
    block = re.search(r'val QuietGrid' + theme + r' = QuietGridColors\((.*?)\n\)', source, re.S).group(1)
    colors = {name: '#' + value for name, value in re.findall(r'(\w+) = Color\(0xFF([0-9A-F]{6})\)', block)}
    pairs = [(fg, bg) for fg in ('ink', 'secondary', 'tertiary', 'danger', 'accentInk') for bg in ('bg', 'surface', 'paper')]
    pairs += [('onAccent', 'cta'), ('accentInk', 'accentSoft')]
    pairs += [('stickerInk', bg) for bg in ('butter', 'popRed', 'mint', 'klein', 'peach', 'bubble')]
    # lilac is icon-only, never used as a body-text background.
    rows = []
    for fg, bg in pairs:
        ratio = contrast(colors[fg], colors[bg])
        assert ratio >= 4.5, f'{theme} {fg}/{bg} {ratio:.4f}:1 FAIL'
        rows.append(dict(foreground=fg, background=bg, foregroundHex=colors[fg], backgroundHex=colors[bg], ratio=round(ratio, 4)))
        print(f'PASS {theme} {fg}/{bg} {ratio:.4f}:1')
    values = ET.parse(RES / ('values-night' if theme == 'Dark' else 'values') / 'colors.xml').getroot()
    native = next(c.text for c in values if c.attrib.get('name') == 'quiet_grid_background')
    assert native == colors['bg']
    for bg in ('bg', 'surface'):
        assert contrast(colors['border'], colors[bg]) >= 3
        assert contrast(colors['shadow'], colors[bg]) >= 3
    measurements[theme.lower()] = dict(tokens=colors, pairs=rows, failures=0)

for sdk in (26, 33):
    for name in ('ic_launcher', 'ic_launcher_round'):
        icon = ET.parse(RES / f'mipmap-anydpi-v{sdk}/{name}.xml').getroot()
        assert icon.tag == 'adaptive-icon'
        for layer in ('background', 'foreground'):
            resource = icon.find(layer).attrib[A+'drawable'].split('/')[-1]
            assert ET.parse(RES / f'drawable/{resource}.xml').getroot().tag == 'vector'
        if sdk == 33:
            assert icon.find('monochrome').attrib[A+'drawable'] == '@drawable/ic_launcher_monochrome'
mono = ET.parse(RES / 'drawable/ic_launcher_monochrome.xml').getroot()
assert all(p.attrib[A+'strokeColor'] == '#FFFFFF' and p.attrib[A+'fillColor'] == '#00000000' for p in mono.iter('path'))
for index, name in enumerate(('burger', 'ramen', 'receipt', 'bite', 'radar', 'price', 'cloche', 'notebook'), 1):
    assert ET.parse(RES / f'drawable/bite_v{index}_{name}.xml').getroot().tag == 'vector'
for res in (RES, FEATURE / 'res'):
    assert not [p for p in res.rglob('*') if p.suffix.lower() in ('.png', '.jpg', '.jpeg', '.webp')]
# A checked-in baseline makes the same assertions work in shallow PR checkouts.
baseline = json.loads((ROOT / 'android/tools/p7_baseline.json').read_text())
for relative, tags in baseline['testTags'].items():
    new_tags = set(re.findall(r'testTag\(("(?:[^"\\]|\\.)*"|tag)\)', (ROOT / relative).read_text()))
    assert set(tags) <= new_tags, (relative, set(tags) - new_tags)
for relative, expected in baseline['protectedHashes'].items():
    assert hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() == expected, relative
print('PASS contrast failures=0, native night colors, visible dark strokes/shadows, vectors, tags, backend/core/data unchanged')
if '--json' in __import__('sys').argv:
    dest = Path(__import__('sys').argv[-1]); dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(measurements, indent=2) + '\n')
