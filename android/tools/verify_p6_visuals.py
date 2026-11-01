#!/usr/bin/env python3
"""Verify the shipped vector resources and the selected navigation colors."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'android/app/src/main/res'
NS = '{http://schemas.android.com/apk/res/android}'


def luminance(color):
    values = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in values]
    return sum(v * weight for v, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


def verify(label, foreground, background):
    a, b = luminance(foreground), luminance(background)
    ratio = (max(a, b) + 0.05) / (min(a, b) + 0.05)
    assert ratio >= 4.5, f'{label}: {ratio:.4f} < 4.5'
    print(f'PASS {label}: {foreground}/{background} contrast={ratio:.4f}:1')


foreground = ET.parse(RES / 'drawable/ic_launcher_foreground.xml').getroot()
background = ET.parse(RES / 'drawable/ic_launcher_background.xml').getroot()
assert foreground.tag == background.tag == 'vector'
fg = {p.attrib[NS + 'fillColor'] for p in foreground.findall('path')}
bg = {p.attrib[NS + 'fillColor'] for p in background.findall('path')}
assert len(fg) == len(bg) == 1
verify('launcher on light and dark wallpaper (opaque background)', next(iter(fg)), next(iter(bg)))
for sdk in (26, 33):
    for name in ('ic_launcher', 'ic_launcher_round'):
        icon = ET.parse(RES / f'mipmap-anydpi-v{sdk}/{name}.xml').getroot()
        assert icon.tag == 'adaptive-icon'
        assert icon.find('background').attrib[NS + 'drawable'] == '@drawable/ic_launcher_background'
        assert icon.find('foreground').attrib[NS + 'drawable'] == '@drawable/ic_launcher_foreground'
        if sdk == 33:
            assert icon.find('monochrome').attrib[NS + 'drawable'] == '@drawable/ic_launcher_foreground'
assert not list(RES.rglob('*.png')) and not list(RES.rglob('*.webp'))

source = (ROOT / 'android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/theme/Colors.kt').read_text()
root = (ROOT / 'android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature/ProductRoot.kt').read_text()
for setting in ('selectedIconColor = c.accentInk', 'selectedTextColor = c.accentInk',
                'indicatorColor = c.accentSoft', 'unselectedIconColor = c.secondary', 'unselectedTextColor = c.secondary'):
    assert setting in root
for theme in ('Light', 'Dark'):
    block = re.search(r'val QuietGrid' + theme + r' = QuietGridColors\((.*?)\n\)', source, re.S).group(1)
    colors = dict(re.findall(r'(\w+) = Color\(0xFF([0-9A-F]{6})\)', block))
    colors = {name: '#' + value for name, value in colors.items()}
    for label, fore, back in (('selected icon', 'accentInk', 'accentSoft'), ('selected label', 'accentInk', 'surface'),
                             ('unselected icon and label', 'secondary', 'surface'), ('body text', 'ink', 'bg')):
        verify(f'{theme.lower()} {label}', colors[fore], colors[back])
    values = ET.parse(RES / ('values-night' if theme == 'Dark' else 'values') / 'colors.xml').getroot()
    native_bg = next(c.text for c in values.findall('color') if c.attrib['name'] == 'quiet_grid_background')
    assert native_bg == colors['bg']
manifest = ET.parse(ROOT / 'android/app/src/main/AndroidManifest.xml').getroot().find('application')
assert manifest.attrib[NS + 'icon'] == '@mipmap/ic_launcher'
assert manifest.attrib[NS + 'roundIcon'] == '@mipmap/ic_launcher_round'
assert manifest.attrib[NS + 'theme'] == '@style/Theme.DiningLog'
print('PASS P6 contrast failures=0; vector/adaptive/round/monochrome resources and matching native night background verified')
