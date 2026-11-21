#!/usr/bin/env python3
"""Preview of the new system-PiP action icons (the layout used by SystemHost.actionTextIcon).

Same throwaway rasteriser as render_pip_icons.py: pure Python, no image library in this sandbox.
The digits are a blocky stand-in for the bold condensed system font, so this shows LAYOUT and
RELATIVE SIZE, not the exact typeface.
"""
import math
import struct
import zlib

UNIT = 24.0
SS = 6

SEG = {
    '0': [(0, 0, 1, 2), (1, 2, 2, 2), (2, 2, 2, 0), (2, 0, 1, 0), (1, 0, 0, 0), (0, 0, 0, 2)],
    '1': [(2, 0, 2, 2), (1.2, 0.4, 2.1, 0.1)],
    '2': [(0, 0, 2, 0), (2, 0, 2, 1), (2, 1, 0, 1), (0, 1, 0, 2), (0, 2, 2, 2)],
    '5': [(2, 0, 0, 0), (0, 0, 0, 1), (0, 1, 2, 1), (2, 1, 2, 2), (2, 2, 0, 2)],
    '8': [(0, 0, 2, 0), (2, 0, 2, 1), (2, 1, 0, 1), (0, 1, 0, 2), (0, 2, 2, 2),
          (0, 0, 0, 1), (2, 0, 2, 1)],
}


def _poly(pts, size):
    edges = []
    n = len(pts)
    for i in range(n):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % n]
        if y0 != y1:
            edges.append((y0, y1, x0, x1))
    rows = [[0.0] * size for _ in range(size)]
    if not edges:
        return rows
    lo = max(0, int(min(e[0] for e in edges)))
    hi = min(size - 1, int(max(e[1] for e in edges)))
    for y in range(lo, hi + 1):
        yc = y + 0.5
        xs = []
        for ya, yb, xa, xb in edges:
            if (ya <= yc < yb) or (yb <= yc < ya):
                xs.append(xa + (yc - ya) / (yb - ya) * (xb - xa))
        xs.sort()
        for i in range(0, len(xs) - 1, 2):
            a, b = xs[i], xs[i + 1]
            for x in range(max(0, int(a)), min(size - 1, int(b))):
                h = min(b, x + 1) - max(a, x)
                if h > 0:
                    rows[y][x] = min(1.0, rows[y][x] + h)
    return rows


def _bar(mask, size, x0, y0, x1, y1, thick):
    dx, dy = x1 - x0, y1 - y0
    n = math.hypot(dx, dy) or 1.0
    px, py = -dy / n * thick / 2, dx / n * thick / 2
    pts = [(x0 + px, y0 + py), (x1 + px, y1 + py), (x1 - px, y1 - py), (x0 - px, y0 - py)]
    cov = _poly(pts, size)
    for y in range(size):
        r, c = mask[y], cov[y]
        for x in range(size):
            if c[x]:
                r[x] = min(1.0, r[x] + c[x])


def digits_mask(text, size, height, condense, weight, x_left, baseline):
    """Draw `text` with its ink box `height` tall, starting at x_left, on `baseline`."""
    mask = [[0.0] * size for _ in range(size)]
    seg_h = height
    seg_w = height * 0.56 * condense      # one glyph box
    gap = height * 0.10
    cx = x_left
    for ch in text:
        if ch == '.':
            d = height * 0.16
            _bar(mask, size, cx, baseline - d / 2, cx + d, baseline - d / 2, d * 1.4)
            cx += d + gap * 0.6
            continue
        for sx0, sy0, sx1, sy1 in SEG.get(ch, []):
            ax = cx + sx0 / 2 * seg_w
            ay = baseline - seg_h + sy0 / 2 * seg_h
            bx = cx + sx1 / 2 * seg_w
            by = baseline - seg_h + sy1 / 2 * seg_h
            _bar(mask, size, ax, ay, bx, by, seg_w * weight)
        cx += seg_w + gap
    return mask, cx - gap - x_left


def arrow_mask(size, forward, left, right, height, cy):
    mask = [[0.0] * size for _ in range(size)]
    if forward:
        pts = [(right, cy), (left, cy - height / 2), (left, cy + height / 2)]
    else:
        pts = [(left, cy), (right, cy - height / 2), (right, cy + height / 2)]
    cov = _poly(pts, size)
    for y in range(size):
        for x in range(size):
            if cov[y][x]:
                mask[y][x] = cov[y][x]
    return mask


def crop(mask, unit_px):
    """Crop to ink, as a square, the way cropToInk() does."""
    h = len(mask)
    l, t, r, b = h, h, -1, -1
    for y in range(h):
        row = mask[y]
        for x in range(h):
            if row[x] > 0.02:
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
    if r < l:
        return mask
    side = max(r - l + 1, b - t + 1)
    side = min(side, h)
    cxm, cym = (l + r) // 2, (t + b) // 2
    x0 = max(0, min(h - side, cxm - side // 2))
    y0 = max(0, min(h - side, cym - side // 2))
    return [row[x0:x0 + side] for row in mask[y0:y0 + side]]


def compose(px_per_unit=6):
    size = int(UNIT * px_per_unit)
    u = px_per_unit

    def build(value, arrow):
        has_arrow = arrow != 0
        has_x = len(value) > 1 and value.endswith('x')
        digits = value[:-1] if has_x else value
        box = size
        zone_l = 0.02 if has_arrow else 0.0
        zone_r = 0.74 if has_arrow else (0.88 if has_x else 0.99)
        max_w = (zone_r - zone_l) * box
        max_h = box * 0.94

        # fit the digits: height-capped, then width-capped
        height = max_h * 0.62          # ink height is about 0.62 of the em size for bold digits
        seg_per_glyph = 0.56 * 0.80    # condensed
        gap = 0.10
        while True:
            w = len([c for c in digits if c != '.']) * seg_per_glyph * height \
                + digits.count('.') * 0.16 * height \
                + (len(digits) - 1) * gap * height
            if w <= max_w or height <= 1:
                break
            height *= max_w / w
        if height > max_h:
            height = max_h

        m = [[0.0] * box for _ in range(box)]
        baseline = box * 0.80 if has_x else box / 2 + height / 2
        start_x = zone_l * box + (max_w - w) / 2 if has_arrow else (zone_r * box - w)
        dm, dw = digits_mask(digits, box, height, 0.80, 0.30, start_x, baseline)
        for y in range(box):
            for x in range(box):
                if dm[y][x]:
                    m[y][x] = min(1.0, m[y][x] + dm[y][x])
        if has_x:
            xh = height * 0.56
            xw = xh * 0.56 * 0.9
            xm, _ = digits_mask('x' if False else '8', box, xh, 0.9, 0.30, box * 0.985 - xw, box * 0.34)
            # a real "x" is two crossing bars; draw that instead of a placeholder glyph
            m2 = [[0.0] * box for _ in range(box)]
            x0 = box * 0.985 - xw
            y0 = box * 0.34 - xh
            _bar(m2, box, x0, y0, x0 + xw, y0 + xh, xh * 0.30)
            _bar(m2, box, x0, y0 + xh, x0 + xw, y0, xh * 0.30)
            for y in range(box):
                for x in range(box):
                    if m2[y][x]:
                        m[y][x] = min(1.0, m[y][x] + m2[y][x])
        if has_arrow:
            am = arrow_mask(box, arrow > 0, box * 0.78, box * 0.98, box * 0.44, box / 2)
            for y in range(box):
                for x in range(box):
                    if am[y][x]:
                        m[y][x] = min(1.0, m[y][x] + am[y][x])
        return crop(m, u)

    cells = [('1x', 0), ('1.25x', 0), ('1.8x', 0), ('2x', 0), ('10', 1), ('10', -1)]
    cell = 190
    cols = len(cells)
    W, H = cell * cols + 20, cell + 20
    pix = [[(26, 28, 32) for _ in range(W)] for _ in range(H)]

    for i, (value, arrow) in enumerate(cells):
        mask = build(value, arrow)
        cx = 10 + i * cell + cell // 2
        cy = 10 + cell // 2
        btn = 116
        for y in range(btn):
            for x in range(btn):
                d = math.hypot(x + .5 - btn / 2, y + .5 - btn / 2)
                if d <= btn / 2:
                    dx = cx - btn // 2 + x
                    dy = cy - btn // 2 + y
                    if 0 <= dx < W and 0 <= dy < H:
                        r, g, b = pix[dy][dx]
                        a = 0.9
                        pix[dy][dx] = (r + (60 - r) * a, g + (64 - g) * a, b + (72 - b) * a)
        s = len(mask)
        inner = int(btn * 0.72)
        for y in range(inner):
            for x in range(inner):
                sy = int(y * s / inner)
                sx = int(x * s / inner)
                a = mask[sy][sx]
                if a <= 0.01:
                    continue
                dx = cx - inner // 2 + x
                dy = cy - inner // 2 + y
                if 0 <= dx < W and 0 <= dy < H:
                    r, g, b = pix[dy][dx]
                    pix[dy][dx] = (r + (255 - r) * a, g + (255 - g) * a, b + (255 - b) * a)
        ink = sum(sum(r) for r in mask) / (s * s)
        print(f'{value:>8} arrow={arrow:>2}  ink={ink:.3f}  cropped={s}x{s}')
    write_png('docs/pip-icon-preview-new.png', pix, W, H)
    print('wrote docs/pip-icon-preview-new.png', W, H)


def write_png(path, pixels, w, h):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            r, g, b = pixels[y][x]
            raw += bytes((max(0, min(255, int(r + .5))), max(0, min(255, int(g + .5))),
                          max(0, min(255, int(b + .5)))))
    def chunk(tag, data):
        return struct.pack('>I', len(data)) + tag + data + \
            struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)


if __name__ == '__main__':
    compose()