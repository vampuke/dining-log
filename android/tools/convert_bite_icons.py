#!/usr/bin/env python3
"""Lossless path/primitive conversion from the authoritative hand-written SVGs.

No rasterization or replacement artwork. Rounded rects/circles/ellipses become
SVG-equivalent arc paths. Inherited SVG strokes are expanded into each path.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)
A = '{' + ANDROID + '}'
RES = ROOT / 'android/app/src/main/res'
FEATURE = ROOT / 'android/feature/src/main/res/drawable'
COLORS = dict(butter='#FFD60A', popRed='#FF595E', mint='#06D6A0', klein='#3A86FF',
              lilac='#8338EC', peach='#FF9F1C', bubble='#FF70A6')
NAMES = ['burger', 'ramen', 'receipt', 'bite', 'radar', 'price', 'cloche', 'notebook']


def color(value):
    if value == 'none': return '#00000000'
    if len(value) == 4: return '#' + ''.join(c * 2 for c in value[1:])
    return value.upper()


def path_data(e):
    a = e.attrib
    def n(key, default=0): return float(a.get(key, default))
    if e.tag == 'path': return a['d']
    if e.tag == 'line': return f'M{n("x1")},{n("y1")}L{n("x2")},{n("y2")}'
    if e.tag == 'polygon': return 'M' + a['points'].replace(' ', ' L') + 'Z'
    if e.tag in ('circle', 'ellipse'):
        x, y = n('cx'), n('cy'); rx, ry = (n('r'), n('r')) if e.tag == 'circle' else (n('rx'), n('ry'))
        return f'M{x-rx},{y}A{rx},{ry} 0 1,0 {x+rx},{y}A{rx},{ry} 0 1,0 {x-rx},{y}Z'
    if e.tag == 'rect':
        x, y, w, h, r = n('x'), n('y'), n('width'), n('height'), n('rx')
        if not r: return f'M{x},{y}h{w}v{h}h{-w}Z'
        return (f'M{x+r},{y}H{x+w-r}A{r},{r} 0 0,1 {x+w},{y+r}V{y+h-r}'
                f'A{r},{r} 0 0,1 {x+w-r},{y+h}H{x+r}A{r},{r} 0 0,1 {x},{y+h-r}'
                f'V{y+r}A{r},{r} 0 0,1 {x+r},{y}Z')
    raise ValueError(e.tag)


def vector(viewport=100, size=100):
    return ET.Element('vector', {A+'width': f'{size}dp', A+'height': f'{size}dp',
                               A+'viewportWidth': str(viewport), A+'viewportHeight': str(viewport)})


def save(root, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(root)
    path.write_bytes(ET.tostring(root, encoding='utf-8', xml_declaration=True) + b'\n')


def paths(parent, svg, mono=False):
    g = svg.find('g')
    for e in g:
        a = dict(g.attrib); a.update(e.attrib)
        attrs = {A+'pathData': path_data(e), A+'fillColor': color(a.get('fill', '#111')),
                 A+'strokeColor': color(a['stroke']), A+'strokeWidth': a['stroke-width'],
                 A+'strokeLineCap': a['stroke-linecap'], A+'strokeLineJoin': a['stroke-linejoin']}
        if mono:
            attrs[A+'fillColor'] = '#00000000'
            attrs[A+'strokeColor'] = '#FFFFFF'
        ET.SubElement(parent, 'path', attrs)


source = (ROOT / 'design/bite/BITE_DESIGN_REFERENCE.html').read_text()
icons = re.findall(r"bg: 'bg-(\w+)',\s*svg: `(.*?)`", source, re.S)
assert len(icons) == 8
for index, ((bg, raw), name) in enumerate(zip(icons, NAMES), 1):
    svg = ET.fromstring(raw)
    root = vector()
    ET.SubElement(root, 'path', {A+'fillColor': COLORS[bg], A+'strokeColor': '#FFFFFF', A+'strokeWidth': '3',
        A+'pathData': path_data(ET.fromstring('<rect x="4" y="4" width="92" height="92" rx="22"/>'))})
    ET.SubElement(root, 'path', {A+'fillColor': '#00000000', A+'strokeColor': '#111111', A+'strokeWidth': '2.5',
        A+'pathData': path_data(ET.fromstring('<rect x="6" y="6" width="88" height="88" rx="20"/>'))})
    paths(root, svg)
    save(root, RES / f'drawable/bite_v{index}_{name}.xml')
    # Brand mark in the library, using the same generated source paths.
    if index == 1:
        save(root, FEATURE / 'bite_v1_burger.xml')
        night = ET.fromstring(ET.tostring(root))
        for path in night.iter('path'):
            if path.attrib.get(A+'strokeColor') == '#FFFFFF': path.set(A+'strokeColor', '#E6DDCA')
        save(night, FEATURE.parent / 'drawable-night/bite_v1_burger.xml')
        for mono in (False, True):
            launcher = vector(108, 108)
            if not mono:
                ET.SubElement(launcher, 'path', {A+'fillColor': '#FFFDF5', A+'strokeColor': '#111111', A+'strokeWidth': '2',
                    A+'pathData': path_data(ET.fromstring('<rect x="25" y="25" width="58" height="58" rx="16"/>'))})
            group = ET.SubElement(launcher, 'group', {A+'scaleX': '.64', A+'scaleY': '.64', A+'translateX': '22', A+'translateY': '22'})
            paths(group, svg, mono)
            save(launcher, RES / f'drawable/ic_launcher_{"monochrome" if mono else "foreground"}.xml')
background = vector(108, 108)
ET.SubElement(background, 'path', {A+'fillColor': '#FFD60A', A+'pathData': 'M0,0H108V108H0Z'})
save(background, RES / 'drawable/ic_launcher_background.xml')
for name in ('ic_launcher', 'ic_launcher_round'):
    p = RES / f'mipmap-anydpi-v33/{name}.xml'
    p.write_text(p.read_text().replace('<monochrome android:drawable="@drawable/ic_launcher_foreground"',
                                      '<monochrome android:drawable="@drawable/ic_launcher_monochrome"'))
print('Converted all 8 reference SVGs; V1 is the adaptive launcher with dedicated alpha-only monochrome.')
