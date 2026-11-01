#!/usr/bin/env python3
"""生成三套视觉风格的设计图（餐厅记录 App）。"""
from PIL import Image, ImageDraw, ImageFont
import os

W, H = 1080, 1920
OUT = "/root/dining_log/docs/mockups"
os.makedirs(OUT, exist_ok=True)

FONT_PATHS = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
]

def find_font(bold=False, size=32):
    for p in FONT_PATHS:
        if os.path.exists(p):
            if bold and "Bold" not in p and "CJK" in p:
                continue
            try:
                return ImageFont.truetype(p, size)
            except Exception:
                continue
    return ImageFont.load_default()

# 检查中文字体
CJK = None
for p in ["/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
          "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
          "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"]:
    if os.path.exists(p):
        CJK = p
        break
print("CJK font:", CJK)


def font(size, bold=False):
    if CJK:
        try:
            f = ImageFont.truetype(CJK, size)
            return f
        except Exception:
            pass
    return find_font(bold, size)


def rounded(draw, box, radius, fill=None, outline=None, width=1):
    draw.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def text(draw, xy, s, f, fill):
    draw.text(xy, s, font=f, fill=fill)


def draw_star(draw, cx, cy, r, fill):
    import math
    pts = []
    for i in range(10):
        ang = -math.pi / 2 + i * math.pi / 5
        rr = r if i % 2 == 0 else r * 0.45
        pts.append((cx + rr * math.cos(ang), cy + rr * math.sin(ang)))
    draw.polygon(pts, fill=fill)


def make(style):
    img = Image.new("RGB", (W, H), style["bg"])
    d = ImageDraw.Draw(img)

    # 状态栏
    text(d, (60, 40), "9:41", font(30), style["on_surface"])
    text(d, (W - 130, 40), "▮▮▮", font(26), style["on_surface"])

    y = 110

    # 页面标题
    text(d, (60, y), "餐厅档案", font(56, True), style["on_surface"])
    y += 90
    text(d, (60, y), "12 家餐厅 · 8 道推荐菜", font(30), style["secondary"])
    y += 100

    # 搜索框
    rounded(d, (60, y, W - 60, y + 96), 20, fill=style["surface"], outline=style["outline"], width=2)
    text(d, (100, y + 28), "搜索餐厅、菜系、菜品", font(32), style["secondary"])
    y += 150

    # 餐厅卡片
    cards = [
        ("川", "兰记川味小馆", "川菜", "18 中山路 2F", 4, "5 菜 · 3 次到店 · 上次 10-28", style["chip1"]),
        ("粵", "陈记烧腊", "粤菜", "9 解放街", 5, "3 菜 · 1 次到店 · 上次 10-20", style["chip2"]),
        ("面", "老张牛肉面", "面食", "45 新华巷", 3, "7 菜 · 6 次到店 · 上次 10-15", style["chip3"]),
    ]

    for (icon, name, cuisine, addr, rating, meta, chipcol) in cards:
        ch = 260
        rounded(d, (60, y, W - 60, y + ch), 24, fill=style["surface"], outline=style["outline"], width=2)

        # 左侧类型色块
        rounded(d, (90, y + 34, 90 + 120, y + 34 + 120), 20, fill=chipcol)
        f = font(52, True)
        tw = d.textlength(icon, font=f)
        text(d, (90 + 60 - tw / 2, y + 34 + 34), icon, f, style["on_chip"])

        # 右侧信息
        tx = 240
        text(d, (tx, y + 36), name, font(38, True), style["on_surface"])
        text(d, (tx, y + 92), f"{cuisine} · {addr}", font(26), style["secondary"])
        text(d, (tx, y + 136), meta, font(26), style["secondary"])

        # 评分
        for i in range(5):
            c = style["star"] if i < rating else style["star_empty"]
            draw_star(d, tx + 18 + i * 42, y + 200, 15, c)
        text(d, (tx + 220, y + 184), f"{rating} / 5", font(28, True), style["on_surface"])

        y += ch + 24

    # 底部导航
    nav_y = H - 150
    rounded(d, (60, nav_y, W - 60, nav_y + 110), 36, fill=style["surface"], outline=style["outline"], width=2)
    items = [("餐厅", True), ("到店", False), ("我的", False)]
    seg = (W - 120) / 3
    for i, (label, active) in enumerate(items):
        cx = 60 + seg * i + seg / 2
        col = style["primary"] if active else style["secondary"]
        tw = d.textlength(label, font=font(30, True))
        text(d, (cx - tw / 2, nav_y + 40), label, font(30, True), col)

    return img


STYLES = {
    "A_qingci": {
        "name": "A. 青瓷菜单",
        "bg": "#F5F8F6", "surface": "#FFFFFF", "primary": "#245C46",
        "on_surface": "#1C2923", "secondary": "#52645A", "outline": "#B8C9BE",
        "chip1": "#DCEDE3", "chip2": "#E8F0DC", "chip3": "#EDE6DA",
        "on_chip": "#245C46", "star": "#E0A02B", "star_empty": "#D9E2DC",
    },
    "B_shimo": {
        "name": "B. 石墨菜单",
        "bg": "#FAFAFA", "surface": "#FFFFFF", "primary": "#1E2022",
        "on_surface": "#1E2022", "secondary": "#6B7070", "outline": "#E0E3E2",
        "chip1": "#E6E8E7", "chip2": "#EEF0E8", "chip3": "#F0ECE8",
        "on_chip": "#1E2022", "star": "#D9E64B", "star_empty": "#E2E5E4",
    },
    "C_shenhai": {
        "name": "C. 深海蓝档案",
        "bg": "#F4F8FC", "surface": "#FFFFFF", "primary": "#234C78",
        "on_surface": "#1A2632", "secondary": "#536579", "outline": "#D3DFEB",
        "chip1": "#DDEAF6", "chip2": "#E3EEF6", "chip3": "#EAF0F6",
        "on_chip": "#234C78", "star": "#E8A33D", "star_empty": "#DCE5EE",
    },
}

for key, st in STYLES.items():
    im = make(st)
    path = f"{OUT}/{key}.png"
    im.save(path)
    print("saved", path, im.size)
