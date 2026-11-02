#!/usr/bin/env python3
"""Retained CI entry point; P7 validates the new palette and vector launcher.

The obsolete single-color D2 icon assumption is replaced by verification of
all eight reference vectors and dedicated monochrome layers. Contrast >=4.5,
native day/night colors, no bitmap assets and tag compatibility stay enforced.
"""
from verify_bite_visuals import *  # noqa: F401,F403
