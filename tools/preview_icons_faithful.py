#!/usr/bin/env python3
"""Faithful preview of the system-PiP action icons.

Renders with the app's own Roboto Bold and follows SystemHost.actionTextIcon step by step:
condensed digits (TextScaleX 0.80), tightened spacing, the "x" in its own raised column,
one arrow for seek, then the ink crop, then the system's fit into the button circle.
"""
import math
import sys

from PIL import Image, ImageDraw, ImageFont, ImageFilter

FONT = '/root/DSH/NFX-Pad/TMessagesProj/src/main/assets/fonts/mw_bold.ttf'
SCALE_X = 0.80
SPACING = -0.04
DENSITY = 3.0            # xxhdpi, the density the screenshots were taken at
BUTTON_DP = 40.0         # what SystemUI gives one action button
INSET = 0.72             # the icon sits inside the button with a margin


def font(size):
    return ImageFont.truetype(FONT, int(round(size)))


def advance(text, size):
    """Width of `text` the way the Java layout measures it."""
    f = font(size)
    w = 0.0
    for i, ch in enumerate(text):
        w += f.getlength(ch) * SCALE_X
        if i:
            w += SPACING * size
    return w


def _stroke(size):
    return max(1.0, size * 0.045)


def draw_run(img, text, size, x_left, baseline, track):
    """Draw `text` the way Paint does: TextScaleX squeezes each glyph, spacing is em-based.

    The stroke of the fake bold never changes the advance, or neighbouring glyphs would overlap.
    """
    f = font(size)
    asc, desc = f.getmetrics()
    stroke = _stroke(size)
    x = x_left
    for i, ch in enumerate(text):
        if i:
            x += track * size
        adv = f.getlength(ch)
        pad = int(math.ceil(stroke)) + 2
        layer = Image.new('RGBA', (int(math.ceil(adv)) + pad * 2, asc + desc + pad * 2), (0, 0, 0, 0))
        ImageDraw.Draw(layer).text((pad, pad + asc), ch, font=f, fill=(255, 255, 255, 255),
                                   anchor='ls', stroke_width=int(round(stroke)),
                                   stroke_fill=(255, 255, 255, 255))
        target_w = max(1, int(round((adv + stroke * 2) * SCALE_X)))
        layer = layer.resize((target_w, layer.height), Image.LANCZOS)
        img.alpha_composite(layer, (int(round(x)) - int(round(stroke)), int(round(baseline - asc)) - int(round(stroke))))
        x += adv * SCALE_X


def build(value, arrow, box):
    """Return an RGBA image of the icon, cropped to its ink like cropToInk()."""
    has_arrow = arrow != 0
    has_x = len(value) > 1 and value.endswith('x')
    digits = value[:-1] if has_x else value
    suffix_scale = 0.62

    zone_l = box * (0.02 if has_arrow else 0.0)
    zone_r = box * (0.74 if has_arrow else 0.99)
    max_w = zone_r - zone_l
    max_h = box * 0.94

    size = max_h
    for _ in range(8):
        w = advance(digits, size)
        if has_x:
            w += advance('x', size * suffix_scale)
        if w <= max_w or size <= 1:
            break
        size *= max_w / w

    f = font(size)
    asc, desc = f.getmetrics()
    if asc + desc > max_h:
        size *= max_h / (asc + desc)
        f = font(size)
        asc, desc = f.getmetrics()

    img = Image.new('RGBA', (box, box), (0, 0, 0, 0))
    baseline = box / 2 + (asc + desc) / 2 - desc
    dw = advance(digits, size)
    xw = advance('x', size * suffix_scale) if has_x else 0.0
    start = zone_l + (max_w - dw - xw) / 2
    draw_run(img, digits, size, start, baseline, SPACING)
    if has_x:
        draw_run(img, 'x', size * suffix_scale, start + dw, baseline, 0.0)

    if has_arrow:
        d = ImageDraw.Draw(img)
        left, right = box * 0.78, box * 0.98
        h = box * 0.44
        cy = box / 2
        if arrow > 0:
            pts = [(right, cy), (left, cy - h / 2), (left, cy + h / 2)]
        else:
            pts = [(left, cy), (right, cy - h / 2), (right, cy + h / 2)]
        d.polygon(pts, fill=(255, 255, 255, 255))

    return crop_ink(img)


def crop_ink(img):
    bbox = img.getbbox()
    if bbox is None:
        return img
    l, t, r, b = bbox
    side = max(r - l, b - t)
    pad = max(1, int(round(side * 0.02)))
    side += 2 * pad
    cx = (l + r) // 2
    cy = (t + b) // 2
    x0 = max(0, min(img.width - min(side, img.width), cx - side // 2))
    y0 = max(0, min(img.height - min(side, img.height), cy - side // 2))
    s = min(side, img.width, img.height)
    return img.crop((x0, y0, x0 + s, y0 + s))


def shadow_of(icon):
    sh = icon.split()[3].point(lambda a: int(a * 0.7))
    pad = max(2, int(icon.width * 0.10))
    canvas = Image.new('L', (icon.width + pad * 2, icon.height + pad * 2), 0)
    canvas.paste(sh, (pad, pad))
    return canvas.filter(ImageFilter.GaussianBlur(pad * 0.5)), pad


def button(size_px, icon):
    """The system's button: a dark disc with the icon fitted inside it."""
    img = Image.new('RGBA', (size_px, size_px), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((0, 0, size_px - 1, size_px - 1), fill=(255, 255, 255, 38))
    inner = int(size_px * INSET)
    ic = icon.resize((inner, inner), Image.LANCZOS)
    sh, pad = shadow_of(ic)
    sh = sh.resize((inner, inner), Image.LANCZOS)
    img.paste(Image.new('RGBA', (inner, inner), (0, 0, 0, 0)), (0, 0))
    x0 = (size_px - inner) // 2
    img.paste((0, 0, 0, 0), (x0, x0), sh)
    img.paste(ic, (x0, x0), ic)
    return img


def main():
    box = int(24 * DENSITY * 1.35)      # the 32dp icon box at this density
    states = [('1x', 0), ('1.25x', 0), ('1.8x', 0), ('2x', 0), ('10', 1), ('10', -1)]

    real_btn = int(BUTTON_DP * DENSITY)      # 120 px: what the device actually shows
    zoom_btn = int(real_btn * 2.4)

    cell = zoom_btn + 40
    W = cell * len(states) + 24
    H = real_btn + zoom_btn + 120
    sheet = Image.new('RGB', (W, H), (18, 20, 24))
    gd = ImageDraw.Draw(sheet)
    for y in range(H):                      # a dark video frame behind the buttons
        t = y / H
        gd.line([(0, y), (W, y)], fill=(int(34 + 46 * t), int(40 + 34 * t), int(52 + 26 * t)))

    label = font(24)
    small = font(19)
    for i, (value, arrow) in enumerate(states):
        icon = build(value, arrow, box)
        cx = 12 + i * cell + cell // 2
        name = value if arrow == 0 else ('+10s' if arrow > 0 else '-10s')
        ImageDraw.Draw(sheet).text((cx, 10), name, font=label, fill=(214, 220, 230), anchor='ma')
        b = button(real_btn, icon)
        sheet.paste(b, (cx - real_btn // 2, 44), b)
        ImageDraw.Draw(sheet).text((cx, 44 + real_btn + 6), 'device size', font=small,
                                   fill=(150, 158, 170), anchor='ma')
        z = button(zoom_btn, icon)
        sheet.paste(z, (cx - zoom_btn // 2, 44 + real_btn + 34), z)
        ink = sum(icon.split()[3].get_flattened_data()) / (255 * icon.width * icon.height)
        print(f'{name:>6} ink={ink:.3f} icon={icon.width}px')

    out = 'docs/pip-icon-preview-faithful.png'
    sheet.save(out)
    print('wrote', out, sheet.size)


if __name__ == '__main__':
    main()
