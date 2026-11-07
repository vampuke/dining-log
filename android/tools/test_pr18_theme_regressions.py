"""Cheap, executed RED for F1/F2/F4; Android geometry/lifecycle needs CI."""
import unittest

from pr18_visual_roles import ROOT, measurements, rating_threshold


class ComponentContrastTest(unittest.TestCase):
    def check_component(self, prefix):
        for row in measurements():
            if row['component'].startswith(prefix):
                with self.subTest(palette=row['palette'], component=row['component'], state=row['state'],
                                  foreground=row['foreground'], background=row['background']):
                    print(f"MEASURE {row['palette']} {row['component']} {row['state']} "
                          f"{row['foregroundHex']}/{row['backgroundHex']} {row['ratio']:.4f}:1", flush=True)
                    self.assertGreaterEqual(row['ratio'], row['minimum'])

    def test_f1_enabled_button_normal_focus_and_press(self):
        self.check_component('BiteButton')

    def test_f1_enabled_field_placeholder_normal_focus_and_error(self):
        self.check_component('BiteTextField.placeholder')

    def test_f2_enabled_unchecked_update_switch_thumb_and_border(self):
        self.check_component('Settings.updateSwitch')

    def test_f1_f4_reachable_high_score_on_actual_default_editor_frame(self):
        self.check_component('RatingEditor.reachableHighScore')


class ReachableRatingTest(unittest.TestCase):
    def check_renderer(self, renderer):
        threshold = rating_threshold(renderer)
        print(f'MEASURE {renderer} actual high-score threshold={threshold} tenths', flush=True)
        self.assertIn('tenths in 10..50', (ROOT /
            'android/core/src/main/kotlin/top/alanvampuke/dininglog/core/Rating.kt').read_text())
        for value, expected in ((39, False), (40, True), (50, True), (None, False)):
            with self.subTest(renderer=renderer, tenths=value):
                actual = value is not None and value >= threshold
                self.assertEqual(expected, actual)
        self.assertEqual(11, len([v for v in range(10, 51) if v >= threshold]))

    def test_f4_score_39_40_50_and_unrated(self):
        self.check_renderer('Score')

    def test_f4_verdict_fill_39_40_50_and_unrated(self):
        self.check_renderer('verdictFill')

    def test_f4_verdict_sticker_39_40_50_and_unrated(self):
        self.check_renderer('VerdictSticker')


if __name__ == '__main__':
    unittest.main(verbosity=2)
