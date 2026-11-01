#!/usr/bin/env python3
"""在三个原型文件末尾注入 fit 覆盖样式：让编辑页评价框完整显示，并消除屏幕内容溢出。"""
import pathlib, re, sys

FILES = ['d1-ledger.html', 'd2-quiet-grid.html', 'd3-signboard.html']
BASE = pathlib.Path('/root/dining_log/design/proto')

BLOCK = """
      /* ---- fit pass: 让编辑页评价全文可见，消除屏幕溢出 ---- */
      #screen-form .screen-content { padding-bottom: 0; }
      #screen-form .toolbar { margin-bottom: 4px; }
      #screen-form .form-title { margin-bottom: 2px; }
      #screen-form .form-title .caption { margin-top: 2px; }
      #screen-form .form-field { margin-bottom: 4px; }
      #screen-form .field-label { margin-bottom: 4px; }
      #screen-form #restaurant-review { height: 132px; line-height: 20px; }
      #screen-form #restaurant-note { height: 40px; }
      #screen-form .rating-help { margin-top: 3px; }
      #screen-detail .review-section { padding-bottom: 10px; }
      #screen-detail .review { line-height: 1.78; }
"""

for f in FILES:
    p = BASE / f
    s = p.read_text()
    if 'fit pass' in s:
        print(f, 'already patched'); continue
    idx = s.rfind('</style>')
    if idx < 0:
        print('NO STYLE', f); continue
    s = s[:idx] + BLOCK + s[idx:]
    p.write_text(s)
    print(f, 'patched, size', len(s))
