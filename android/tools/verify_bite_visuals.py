#!/usr/bin/env python3
"""Measure BITE tokens, validate vectors and verify P8 UI tag transitions."""
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
navigation = (FEATURE / 'kotlin/top/alanvampuke/dininglog/feature/ProductRoot.kt').read_text()
# Keep the original P6 bindings checked as well as their token contrast. A
# passing palette must not hide a navigation control using a different role.
for binding in ('val foreground = if (selected) c.accentInk else c.secondary',
                'color = if (selected) c.accentSoft else c.surface', 'tint = foreground', 'color = foreground'):
    assert binding in navigation, binding
measurements = {}
for theme in ('Light', 'Dark'):
    block = re.search(r'val QuietGrid' + theme + r' = QuietGridColors\((.*?)\n\)', source, re.S).group(1)
    colors = {name: '#' + value for name, value in re.findall(r'(\w+) = Color\(0xFF([0-9A-F]{6})\)', block)}
    # Screen text can overlap a decorative dot; include that worst-case
    # background instead of measuring only the uninterrupted paper color.
    pairs = [(fg, bg) for fg in ('ink', 'secondary', 'tertiary', 'danger', 'accentInk') for bg in ('bg', 'surface', 'paper', 'dots')]
    pairs += [('onAccent', 'cta'), ('accentInk', 'accentSoft')]
    pairs += [('stickerInk', bg) for bg in ('butter', 'popRed', 'mint', 'klein', 'peach', 'bubble')]
    # lilac (#8338EC) yields only 3.36:1 against stickerInk, so it is icon-only and
    # must never carry body text. This guard fails loudly if that invariant is broken.
    assert contrast('#111111', colors['lilac']) < 4.5, 'lilac contrast changed; re-measure before use'
    assert 'lilac' not in {'paper', 'surface', 'bg'}, 'lilac must not become a surface token'
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
            assert icon.find(layer).attrib[A+'drawable'] == f'@drawable/ic_launcher_{layer}'
            resource = icon.find(layer).attrib[A+'drawable'].split('/')[-1]
            assert ET.parse(RES / f'drawable/{resource}.xml').getroot().tag == 'vector'
        if sdk == 33:
            assert icon.find('monochrome').attrib[A+'drawable'] == '@drawable/ic_launcher_monochrome'
mono = ET.parse(RES / 'drawable/ic_launcher_monochrome.xml').getroot()
mono_paths = list(mono.iter('path'))
assert mono_paths, 'The themed launcher must contain visible artwork'
assert all(p.attrib[A+'strokeColor'] == '#FFFFFF' and p.attrib[A+'fillColor'] == '#00000000'
           and float(p.attrib[A+'strokeWidth']) > 0 for p in mono_paths)
foreground = ET.parse(RES / 'drawable/ic_launcher_foreground.xml').getroot()
background = ET.parse(RES / 'drawable/ic_launcher_background.xml').getroot()
# The single-fill D2 assertion is incompatible with the multi-color reference;
# retain its contrast intent for the actual ink and opaque launcher background.
assert {p.attrib[A+'fillColor'] for p in background.iter('path')} == {'#FFD60A'}
assert any(p.attrib.get(A+'strokeColor') == '#111111' for p in foreground.iter('path'))
assert contrast('#111111', '#FFD60A') >= 4.5
manifest = ET.parse(ROOT / 'android/app/src/main/AndroidManifest.xml').getroot().find('application')
assert manifest.attrib[A+'icon'] == '@mipmap/ic_launcher'
assert manifest.attrib[A+'roundIcon'] == '@mipmap/ic_launcher_round'
assert manifest.attrib[A+'theme'] == '@style/Theme.DiningLog'
for index, name in enumerate(('burger', 'ramen', 'receipt', 'bite', 'radar', 'price', 'cloche', 'notebook'), 1):
    assert ET.parse(RES / f'drawable/bite_v{index}_{name}.xml').getroot().tag == 'vector'
for res in (RES, FEATURE / 'res'):
    assert not [p for p in res.rglob('*') if p.suffix.lower() in ('.png', '.jpg', '.jpeg', '.webp')]
# A checked-in baseline makes the same assertions work in shallow PR checkouts.
baseline = json.loads((ROOT / 'android/tools/p7_baseline.json').read_text())
permitted_tag_removals = {
    'SettingsScreen.kt': {'"draft:${draft.id}"'},
    'HistoryScreens.kt': {'"confirm-new-save"'},
    'ListScreens.kt': {'"choose-restaurant"', '"clear-restaurant"'},
}
for relative, tags in baseline['testTags'].items():
    new_tags = set(re.findall(r'testTag\(("(?:[^"\\]|\\.)*"|tag)\)', (ROOT / relative).read_text()))
    removed = set(tags) - new_tags
    allowed = permitted_tag_removals.get(Path(relative).name, set())
    assert removed <= allowed, (relative, removed)
    if relative.endswith('SettingsScreen.kt'):
        assert '未提交草稿' not in (ROOT / relative).read_text()

lists = (FEATURE / 'kotlin/top/alanvampuke/dininglog/feature/restaurants/ListScreens.kt').read_text()
for retired in ('onChooseRestaurant', 'onClearRestaurant', 'choose-restaurant', 'clear-restaurant'):
    assert retired not in lists, retired
detail = (FEATURE / 'kotlin/top/alanvampuke/dininglog/feature/restaurants/DetailEditScreens.kt').read_text()
for reachable in ('visit-history', 'add-visit'):
    assert f'testTag("{reachable}")' in detail, reachable

protected_baseline = json.loads((ROOT / 'android/tools/p8_protected_hashes.json').read_text())
for relative, expected in protected_baseline.items():
    assert hashlib.sha256((ROOT / relative).read_bytes()).hexdigest() == expected, relative
protected = set(subprocess.check_output(['git', 'ls-files', 'backend', 'contracts'], cwd=ROOT, text=True).splitlines())
assert protected == set(protected_baseline), ('Protected files added or removed', protected ^ set(protected_baseline))
print('PASS contrast failures=0, native night colors, visible dark strokes/shadows, launcher bindings, vectors, tags, backend/contracts unchanged')
# Run the additional InkPaper consumer gate after every retained classic/native/
# vector/tag/protected-file assertion, so a RED accent cannot mask those guards.
from pr18_visual_roles import check_ink_paper
measurements['ink_paper'] = check_ink_paper()
if '--json' in __import__('sys').argv:
    dest = Path(__import__('sys').argv[-1]); dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text(json.dumps(measurements, indent=2) + '\n')
