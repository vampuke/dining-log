"""Source-bound measurements of the PR18 component roles (no Android runtime).

The bindings, palettes and rating thresholds are read from production Kotlin.
An unfamiliar binding fails rather than silently measuring an obsolete token.
Decorative rules and disabled controls are deliberately outside the control gate.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / 'android/feature/src/main/kotlin/top/alanvampuke/dininglog/feature'


def source(name):
    return (KOTLIN / name).read_text()


def palette(name):
    block = re.search(r'val ' + name + r' = QuietGridColors\((.*?)\n\)',
                      source('theme/Colors.kt'), re.S)
    assert block, f'missing palette {name}'
    return {key: '#' + rgb for key, rgb in
            re.findall(r'(\w+) = Color\(0xFF([0-9A-F]{6})\)', block.group(1))}


def function(name, filename):
    text = source(filename)
    start = re.search(r'fun ' + name + r'\(', text)
    assert start, f'missing consumer {name}'
    end = re.search(r'\n(?:@Composable|@OptIn|fun |val )', text[start.end():])
    return text[start.start():start.end() + end.start()] if end else text[start.start():]


def ink_branch(text):
    marker = 'if (LocalThemeStyle.current.isInkPaper)'
    assert marker in text, 'InkPaper consumer must be measured explicitly'
    branch = text.split(marker, 1)[1]
    if branch.lstrip().startswith('{'):
        # These branches contain no braces inside Kotlin strings.
        start = branch.index('{')
        level = 0
        for index in range(start, len(branch)):
            if branch[index] == '{':
                level += 1
            elif branch[index] == '}':
                level -= 1
                if level == 0:
                    return branch[start + 1:index]
        raise AssertionError('unclosed InkPaper branch')
    return re.split(r'\n    (?:else\b|return when)', branch, maxsplit=1)[0]


def token_binding(text, role):
    binding = re.search(r'\b' + role + r'\s*=\s*(?:c\.|LocalQuietGridColors.current\.)(\w+)', text)
    assert binding, f'unsupported actual {role} binding; update the role reader'
    return binding.group(1)


def button_roles(style):
    text = function('BiteButton', 'components/BiteComponents.kt')
    if style == 'InkPaper':
        text = ink_branch(text)
    else:
        text = text.split('val source = remember', 1)[1]
    defaults = text.split('ButtonDefaults.buttonColors(', 1)[1].split('), content', 1)[0]
    return token_binding(defaults, 'contentColor'), token_binding(defaults, 'containerColor')


def placeholder_roles():
    text = function('BiteTextField', 'components/BiteComponents.kt')
    slots = re.search(r'LocalContentColor provides c\.(\w+), LocalFieldForeground provides c\.(\w+)',
                      text[text.index('placeholder ='):])
    assert slots and slots.group(1) == slots.group(2), 'placeholder slot foregrounds diverged'
    result = {}
    for state in ('focused', 'unfocused', 'error'):
        foreground = token_binding(text, state + 'PlaceholderColor')
        assert foreground == slots.group(1), f'{state} Material and GridText placeholder roles diverged'
        result[state] = (foreground, token_binding(text, state + 'ContainerColor'))
    return result


def switch_roles(style):
    settings = source('settings/SettingsScreen.kt')
    assert 'Switch(checked = updateState.enabled' in settings, 'measure the actual Settings update switch'
    colors = source('theme/Colors.kt')
    outline = token_binding(colors, 'outline')
    track = token_binding(colors, 'surfaceContainerHighest')
    if 'SwitchDefaults.colors(' not in settings or style == 'QuietGrid':
        return {'thumb': outline, 'border': outline, 'track': track, 'adjoining': 'bg'}
    # A narrow explicit Settings override is also supported, without substituting
    # decorative border/borderStrong values for the actual functional roles.
    explicit = settings.split('SwitchDefaults.colors(', 1)[1].split(')', 1)[0]
    return {'thumb': token_binding(explicit, 'uncheckedThumbColor'),
            'border': token_binding(explicit, 'uncheckedBorderColor'),
            'track': token_binding(explicit, 'uncheckedTrackColor'), 'adjoining': 'bg'}


def luminance(color):
    values = [int(color[i:i + 2], 16) / 255 for i in (1, 3, 5)]
    linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in values]
    return sum(v * weight for v, weight in zip(linear, (.2126, .7152, .0722)))


def contrast(foreground, background):
    a, b = luminance(foreground), luminance(background)
    return (max(a, b) + .05) / (min(a, b) + .05)


def composite(foreground, background, opacity):
    # Keep the unrounded sRGB components used by Material's state layer.
    return tuple((int(foreground[i:i + 2], 16) * opacity +
                  int(background[i:i + 2], 16) * (1 - opacity)) / 255 for i in (1, 3, 5))


def composite_contrast(foreground, background, opacity):
    values = composite(foreground, background, opacity)
    linear = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in values]
    back = sum(v * weight for v, weight in zip(linear, (.2126, .7152, .0722)))
    front = luminance(foreground)
    return (max(front, back) + .05) / (min(front, back) + .05)


def measurements(styles=('InkPaper', 'QuietGrid')):
    rows = []
    for style in styles:
        for mode in ('Light', 'Dark'):
            colors = palette(style + mode)

            def add(component, state, foreground, background, minimum, opacity=0):
                ratio = composite_contrast(colors[foreground], colors[background], opacity) if opacity else \
                    contrast(colors[foreground], colors[background])
                rows.append(dict(palette=style + mode, component=component, state=state,
                                 foreground=foreground, background=background,
                                 foregroundHex=colors[foreground], backgroundHex=colors[background],
                                 stateLayerOpacity=opacity, ratio=ratio, minimum=minimum))

            foreground, background = button_roles(style)
            # Material3 1.3.2 StateTokens/Ripple: FocusStateLayerOpacity and
            # PressedStateLayerOpacity are both .10; the layer uses contentColor.
            for state, opacity in (('normal', 0), ('focused', .10), ('pressed', .10)):
                add('BiteButton', state, foreground, background, 4.5, opacity)
            for state, (foreground, background) in placeholder_roles().items():
                add('BiteTextField.placeholder', state, foreground, background, 4.5)
            if style == 'InkPaper':
                score = ink_branch(function('Score', 'components/QuietGridComponents.kt'))
                high = re.search(r'tenths >= \d+ -> c\.(\w+)', score)
                frame = re.search(r'Modifier.brutalBox\(LocalQuietGridColors.current\.(\w+)',
                                  function('RatingEditor', 'components/QuietGridComponents.kt'))
                assert high and frame, 'read the actual Score/high and default RatingEditor frame bindings'
                # F4 makes this branch reachable at 40. Check its real default
                # component frame now rather than introducing inaccessible text
                # when the threshold is corrected. This is not a divider gate.
                add('RatingEditor.reachableHighScore', 'required-40-tenths', high.group(1), frame.group(1), 4.5)
            switch = switch_roles(style)
            for component in ('thumb', 'border'):
                for background in ('track', 'adjoining'):
                    add('Settings.updateSwitch.' + component, 'enabled-unchecked',
                        switch[component], switch[background], 3)
    return rows


RENDERERS = {
    'Score': 'components/QuietGridComponents.kt',
    'verdictFill': 'components/BiteComponents.kt',
    'VerdictSticker': 'components/BiteComponents.kt',
}


def rating_threshold(renderer):
    text = ink_branch(function(renderer, RENDERERS[renderer]))
    values = re.findall(r'tenths >= (\d+)', text)
    assert len(values) == 1, f'{renderer} must have one measured high-rating boundary: {values}'
    return int(values[0])


def check_ink_paper():
    rows = measurements(('InkPaper',))
    failures = []
    for row in rows:
        ok = row['ratio'] >= row['minimum']
        print(f"{'PASS' if ok else 'FAIL'} {row['palette']} {row['component']} {row['state']} "
              f"{row['foreground']}/{row['background']} {row['ratio']:.4f}:1 >= {row['minimum']}:1")
        if not ok:
            failures.append(row)
    assert not failures, f'InkPaper actual component contrast failures={len(failures)}'
    return rows
