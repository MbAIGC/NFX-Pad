#!/usr/bin/env python3
"""Render the system-PiP action-button candidates into one comparison sheet.

No image library is available here, so this file carries its own SVG path parser (M/L/H/V/C/S/Q/T/A/Z,
both fill rules), its own scanline rasteriser and its own PNG writer. It is a throwaway tool: it only
exists to look at the candidates side by side.
"""
import math
import re
import struct
import zlib

SS = 8          # supersampling factor per 24dp unit
UNIT = 24.0     # viewport of every candidate


# ---------------------------------------------------------------- path parsing
def _floats(text):
    return [float(v) for v in re.findall(r'[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?', text)]


def _tokenize(d):
    """Yield (command, argument list) pairs."""
    i = 0
    out = []
    cur = None
    buf = ''
    while i < len(d):
        ch = d[i]
        if ch.isalpha():
            if cur is not None:
                out.append((cur, _floats(buf)))
            cur = ch
            buf = ''
        else:
            buf += ch
        i += 1
    if cur is not None:
        out.append((cur, _floats(buf)))
    return out


def _arc_to_poly(x0, y0, rx, ry, phi, large, sweep, x1, y1, steps=24):
    """SVG F.6.5 endpoint -> centre parameterisation, sampled into points."""
    if rx == 0 or ry == 0:
        return [(x1, y1)]
    phi = math.radians(phi)
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x1) / 2.0, (y0 - y1) / 2.0
    x1p = cos_p * dx2 + sin_p * dy2
    y1p = -sin_p * dx2 + cos_p * dy2
    rx, ry = abs(rx), abs(ry)
    lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
    if lam > 1:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = math.sqrt(max(0.0, num / den)) if den else 0.0
    if large == sweep:
        co = -co
    cxp = co * (rx * y1p / ry)
    cyp = -co * (ry * x1p / rx)
    cx = cos_p * cxp - sin_p * cyp + (x0 + x1) / 2.0
    cy = sin_p * cxp + cos_p * cyp + (y0 + y1) / 2.0
    # angle of the start point, then the sweep
    def ang(ux, uy, vx, vy):
        dot = ux * vx + uy * vy
        n = math.hypot(ux, uy) * math.hypot(vx, vy)
        if n == 0:
            return 0.0
        a = math.acos(max(-1.0, min(1.0, dot / n)))
        return -a if (ux * vy - uy * vx) < 0 else a
    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    pts = []
    for i in range(1, steps + 1):
        t = t1 + dt * i / steps
        px = cos_p * rx * math.cos(t) - sin_p * ry * math.sin(t) + cx
        py = sin_p * rx * math.cos(t) + cos_p * ry * math.sin(t) + cy
        pts.append((px, py))
    return pts


def flatten(d, steps_curve=16):
    """Path data -> list of (points, closed) subpaths in viewport units."""
    subs = []
    pts = []
    cx = cy = 0.0
    sx = sy = 0.0
    last_ctrl = None
    closed = False

    def flush():
        nonlocal pts, closed
        if len(pts) > 2:
            subs.append((list(pts), closed))
        pts = []
        closed = False

    for cmd, args in _tokenize(d):
        rel = cmd.islower()
        c = cmd.upper()
        k = 0
        while True:
            rest = args[k:]
            if c == 'M':
                if len(rest) < 2:
                    break
                x, y = rest[0], rest[1]
                if rel:
                    x, y = cx + x, cy + y
                flush()
                cx, cy = x, y
                sx, sy = x, y
                pts = [(cx, cy)]
                k += 2
                c = 'L'      # subsequent pairs are lineto
                rel = cmd.islower()
                continue
            if c == 'L':
                if len(rest) < 2:
                    break
                x, y = rest[0], rest[1]
                if rel:
                    x, y = cx + x, cy + y
                cx, cy = x, y
                pts.append((cx, cy))
                k += 2
                continue
            if c == 'H':
                if len(rest) < 1:
                    break
                x = rest[0] + (cx if rel else 0)
                cx = x
                pts.append((cx, cy))
                k += 1
                continue
            if c == 'V':
                if len(rest) < 1:
                    break
                y = rest[0] + (cy if rel else 0)
                cy = y
                pts.append((cx, cy))
                k += 1
                continue
            if c in ('C', 'S'):
                need = 6 if c == 'C' else 4
                if len(rest) < need:
                    break
                if c == 'C':
                    x1, y1, x2, y2, x, y = rest[:6]
                    if rel:
                        x1, y1, x2, y2, x, y = cx + x1, cy + y1, cx + x2, cy + y2, cx + x, cy + y
                else:
                    x2, y2, x, y = rest[:4]
                    if rel:
                        x2, y2, x, y = cx + x2, cy + y2, cx + x, cy + y
                    if last_ctrl is None:
                        x1, y1 = cx, cy
                    else:
                        x1, y1 = 2 * cx - last_ctrl[0], 2 * cy - last_ctrl[1]
                p0 = (cx, cy)
                for i in range(1, steps_curve + 1):
                    t = i / steps_curve
                    mt = 1 - t
                    bx = mt ** 3 * p0[0] + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t ** 3 * x
                    by = mt ** 3 * p0[1] + 3 * mt * mt * t * y1 + 3 * mt * t * t * y2 + t ** 3 * y
                    pts.append((bx, by))
                last_ctrl = (x2, y2)
                cx, cy = x, y
                k += need
                continue
            if c in ('Q', 'T'):
                need = 4 if c == 'Q' else 2
                if len(rest) < need:
                    break
                if c == 'Q':
                    qx, qy, x, y = rest[:4]
                    if rel:
                        qx, qy, x, y = cx + qx, cy + qy, cx + x, cy + y
                else:
                    x, y = rest[:2]
                    if rel:
                        x, y = cx + x, cy + y
                    if last_ctrl is None:
                        qx, qy = cx, cy
                    else:
                        qx, qy = 2 * cx - last_ctrl[0], 2 * cy - last_ctrl[1]
                p0 = (cx, cy)
                for i in range(1, steps_curve + 1):
                    t = i / steps_curve
                    mt = 1 - t
                    bx = mt * mt * p0[0] + 2 * mt * t * qx + t * t * x
                    by = mt * mt * p0[1] + 2 * mt * t * qy + t * t * y
                    pts.append((bx, by))
                last_ctrl = (qx, qy)
                cx, cy = x, y
                k += need
                continue
            if c == 'A':
                if len(rest) < 7:
                    break
                rx, ry, phi, large, sweep, x, y = rest[:7]
                if rel:
                    x, y = cx + x, cy + y
                pts.extend(_arc_to_poly(cx, cy, rx, ry, phi, int(large), int(sweep), x, y))
                last_ctrl = None
                cx, cy = x, y
                k += 7
                continue
            if c == 'Z':
                closed = True
                pts.append((sx, sy))
                cx, cy = sx, sy
                last_ctrl = None
                flush()
                break
            break
    flush()
    return subs


# ------------------------------------------------------------------ rasterise
def fill(subpaths, fill_type, size):
    """Scanline fill at `size` px per 24 units. Returns a coverage list of lists (0..1)."""
    scale = size / UNIT
    rows = [[0.0] * size for _ in range(size)]
    edges = []
    for pts, closed in subpaths:
        n = len(pts)
        for i in range(n - 1 if not closed else n):
            x0, y0 = pts[i]
            x1, y1 = pts[(i + 1) % n]
            if y0 == y1:
                continue
            edges.append((y0 * scale, y1 * scale, x0 * scale, x1 * scale))
    if not edges:
        return rows
    ymin = max(0, int(math.floor(min(e[0] for e in edges))))
    ymax = min(size - 1, int(math.ceil(max(e[1] for e in edges))))
    for y in range(ymin, ymax + 1):
        yc = y + 0.5
        xs = []
        for ya, yb, xa, xb in edges:
            if (ya <= yc < yb) or (yb <= yc < ya):
                t = (yc - ya) / (yb - ya)
                xs.append((xa + t * (xb - xa), 1 if yb > ya else -1))
        if not xs:
            continue
        xs.sort()
        row = rows[y]
        if fill_type == 'evenOdd':
            for i in range(0, len(xs) - 1, 2):
                x_from, x_to = xs[i][0], xs[i + 1][0]
                _span(row, x_from, x_to, size)
        else:
            wind = 0
            for i in range(len(xs) - 1):
                wind += xs[i][1]
                if wind != 0:
                    _span(row, xs[i][0], xs[i + 1][0], size)
    return rows


def _span(row, x_from, x_to, size):
    if x_to < 0 or x_from > size:
        return
    x_from = max(0.0, x_from)
    x_to = min(float(size), x_to)
    for x in range(int(math.floor(x_from)), min(size - 1, int(math.ceil(x_to)))):
        a = max(x_from, x)
        b = min(x_to, x + 1)
        if b > a:
            row[x] = min(1.0, row[x] + (b - a))


def blend(dst, cov, rgb, box, alpha=1.0):
    """Composite a coverage mask into dst at box=(x, y)."""
    bx, by = box
    h = len(cov)
    for y in range(h):
        dy = by + y
        if dy < 0 or dy >= len(dst):
            continue
        row = cov[y]
        drow = dst[dy]
        for x in range(len(row)):
            c = row[x] * alpha
            if c <= 0.004:
                continue
            dx = bx + x
            if dx < 0 or dx >= len(drow):
                continue
            _, _, _ = 0, 0, 0
            dr, dg, db = drow[dx]
            drow[dx] = (
                dr + (rgb[0] - dr) * c,
                dg + (rgb[1] - dg) * c,
                db + (rgb[2] - db) * c,
            )


# ----------------------------------------------------------------- PNG writer
def write_png(path, pixels, w, h):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            r, g, b = pixels[y][x]
            raw += bytes((max(0, min(255, int(r + .5))),
                          max(0, min(255, int(g + .5))),
                          max(0, min(255, int(b + .5)))))

    def chunk(tag, data):
        c = struct.pack('>I', len(data)) + tag + data
        return c + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff)

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    open(path, 'wb').write(png)


# ------------------------------------------------------------ the candidates
def load_vector(path):
    """Return [(subpaths, fillType)] for each <path> of a VectorDrawable."""
    text = open(path, encoding='utf-8').read()
    out = []
    for m in re.finditer(r'<path\b(.*?)/>', text, re.S):
        body = m.group(1)
        d = re.search(r'android:pathData\s*=\s*"([^"]+)"', body)
        if not d:
            continue
        ft = re.search(r'android:fillType\s*=\s*"([^"]+)"', body)
        out.append((flatten(d.group(1)), ft.group(1) if ft else 'nonZero'))
    return out


def _covers_all(subpaths):
    xs = [x for pts, _ in subpaths for x, _ in pts]
    ys = [y for pts, _ in subpaths for _, y in pts]
    if not xs:
        return False
    return (max(xs) - min(xs)) >= 23 and (max(ys) - min(ys)) >= 23


def render_layers(layers, size):
    """Composite every path of a vector into one coverage mask."""
    acc = [[0.0] * size for _ in range(size)]
    for subpaths, ft in layers:
        if _covers_all(subpaths):
            # a full-canvas rect is a background or tint layer, not part of the glyph
            continue
        cov = fill(subpaths, ft, size)
        for y in range(size):
            row, arow = cov[y], acc[y]
            for x in range(size):
                c = row[x]
                if c:
                    arow[x] = min(1.0, arow[x] + c - arow[x] * c)
    return acc


# blocky bold digits: enough to approximate what drawText() produces at this weight
SEGMENTS = {
    '0': [(0, 0, 1, 2), (1, 2, 2, 2), (2, 2, 2, 0), (2, 0, 1, 0), (1, 0, 0, 0), (0, 0, 0, 2)],
    '1': [(2, 0, 2, 2), (1.1, 0.35, 2.1, 0.0)],
    '2': [(0, 0, 2, 0), (2, 0, 2, 1), (2, 1, 0, 1), (0, 1, 0, 2), (0, 2, 2, 2)],
    '5': [(2, 0, 0, 0), (0, 0, 0, 1), (0, 1, 2, 1), (2, 1, 2, 2), (2, 2, 0, 2)],
}
DOT = [(0, 0, 0.35, 0)]


def text_mask(text, size, weight=0.30, glyph_h=0.62, center=(0.5, 0.5)):
    """A crude bold text mask: digits as thick strokes, so the visual weight can be compared."""
    mask = [[0.0] * size for _ in range(size)]
    tallest = glyph_h
    dig_w = 0.34
    gap = 0.10
    dot_w = 0.10
    total = 0.0
    for ch in text:
        total += dot_w if ch == '.' else dig_w
        total += gap
    total -= gap
    x = center[0] - total / 2.0
    top = center[1] - tallest / 2.0
    for ch in text:
        if ch == '.':
            _blit_segments([(0, 0.9, dot_w, 0.9)], mask, size, x, top + tallest * 0.9, dot_w, 0.0, weight)
            x += dot_w + gap
            continue
        segs = SEGMENTS.get(ch)
        if not segs:
            continue
        _blit_segments(segs, mask, size, x, top, dig_w, tallest, weight)
        x += dig_w + gap
    return mask


def _blit_segments(segs, mask, size, x0, y0, w, h, weight):
    for sx0, sy0, sx1, sy1 in segs:
        ax, ay = x0 + sx0 / 2.0 * w, y0 + sy0 / 2.0 * h
        bx, by = x0 + sx1 / 2.0 * w, y0 + sy1 / 2.0 * h
        t = weight
        pts = []
        dx, dy = bx - ax, by - ay
        n = math.hypot(dx, dy) or 1.0
        px, py = -dy / n * t / 2.0, dx / n * t / 2.0
        for cx, cy in ((ax + px, ay + py), (bx + px, by + py), (bx - px, by - py), (ax - px, ay - py)):
            pts.append((cx * size, cy * size))
        for y in range(size):
            for xx in range(size):
                pass
        cov = _fill_poly_direct(pts, size)
        for y in range(size):
            for xx in range(size):
                c = cov[y][xx]
                if c:
                    mask[y][xx] = min(1.0, mask[y][xx] + c)


def _fill_poly_direct(pts, size):
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
    ymin = max(0, int(min(e[0] for e in edges)))
    ymax = min(size - 1, int(max(e[1] for e in edges)))
    for y in range(ymin, ymax + 1):
        yc = y + 0.5
        xs = []
        for ya, yb, xa, xb in edges:
            if (ya <= yc < yb) or (yb <= yc < ya):
                xs.append(xa + (yc - ya) / (yb - ya) * (xb - xa))
        if len(xs) < 2:
            continue
        xs.sort()
        for i in range(0, len(xs) - 1, 2):
            _span(rows[y], xs[i], xs[i + 1], size)
    return rows


def arrow_mask(size, forward=True, count=2):
    """The direction marker used by the text-rendered seek entry."""
    mask = [[0.0] * size for _ in range(size)]
    w = size * 0.10
    h = size * 0.13
    right = size * 0.94
    cy = size * 0.5
    for i in range(count):
        tip = right - i * w * 1.15
        base = tip - w
        if forward:
            pts = [(tip, cy), (base, cy - h), (base, cy + h)]
        else:
            pts = [(base, cy), (tip, cy - h), (tip, cy + h)]
        cov = _fill_poly_direct(pts, size)
        for y in range(size):
            for x in range(size):
                if cov[y][x]:
                    mask[y][x] = min(1.0, mask[y][x] + cov[y][x])
    return mask

# ------------------------------------------------------------------- the sheet
def main():
    res = {k: 96 * SS // 4 for k in ('icon',)}   # 192 px per 24 units
    icon_px = 192

    def vec_mask(path):
        layers = load_vector(path)
        return render_layers(layers, icon_px)

    cands = []
    res_dir = 'TMessagesProj/src/main/res/drawable/'

    cands.append(('1', 'nfxp_forward_10 (current)', vec_mask(res_dir + 'nfxp_forward_10.xml')))
    cands.append(('2', 'baseline_fast_forward_24', vec_mask(res_dir + 'baseline_fast_forward_24.xml')))
    cands.append(('3', 'baseline_forward_24', vec_mask(res_dir + 'baseline_forward_24.xml')))
    cands.append(('4', 'msg_speed_remix (vector)', vec_mask(res_dir + 'msg_speed_remix.xml')))

    t10 = text_mask('10', icon_px, weight=0.20, glyph_h=0.56, center=(0.42, 0.5))
    am = arrow_mask(icon_px, forward=True, count=2)
    for y in range(icon_px):
        for x in range(icon_px):
            t10[y][x] = min(1.0, t10[y][x] + am[y][x])
    cands.append(('5', 'text "10" + arrows (new)', t10))

    cands.append(('6', 'text "1.25" (speed now)', text_mask('1.25', icon_px, weight=0.18, glyph_h=0.52)))

    try:
        cands.append(('7', 'avd_speed (speedometer)', vec_mask(res_dir + 'avd_speed.xml')))
    except Exception as e:
        print('avd_speed skipped:', e)

    cols = 4
    rows = (len(cands) + cols - 1) // cols
    cell_w, cell_h = 210, 190
    pad = 10
    sheet_w = cols * cell_w + pad * 2
    sheet_h = rows * cell_h + pad * 2

    # background: a dark video frame, so the buttons are judged the way they are seen in PiP
    pix = [[(28, 30, 34) for _ in range(sheet_w)] for _ in range(sheet_h)]

    for i, (num, label, mask) in enumerate(cands):
        cx0 = pad + (i % cols) * cell_w
        cy0 = pad + (i // cols) * cell_h
        # the system's button: a circle of the size it gives an action, icon inside it
        btn_d = 104
        bx = cx0 + (cell_w - btn_d) // 2
        by = cy0 + 12
        disc = [[0.0] * btn_d for _ in range(btn_d)]
        r = btn_d / 2.0
        for y in range(btn_d):
            for x in range(btn_d):
                d = math.hypot(x + .5 - r, y + .5 - r)
                disc[y][x] = 1.0 if d <= r - 1 else max(0.0, (r - d))
        blend(pix, disc, (58, 62, 70), (bx, by), 1.0)
        # icon at the system's usual inset (about 0.72 of the button)
        inner = int(btn_d * 0.72)
        small = _downsample(mask, icon_px, inner)
        blend(pix, small, (255, 255, 255), (bx + (btn_d - inner) // 2, by + (btn_d - inner) // 2))
        # index digit under the button
        num_mask = text_mask(num, 48, weight=0.30, glyph_h=0.55)
        blend(pix, num_mask, (168, 174, 184), (cx0 + cell_w // 2 - 24, by + btn_d + 10))
        print(f'{num}: {label}  (ink {sum(sum(r) for r in mask) / (icon_px * icon_px):.3f})')

    write_png('docs/pip-icon-candidates.png', pix, sheet_w, sheet_h)
    print('wrote docs/pip-icon-candidates.png', sheet_w, 'x', sheet_h)


def _downsample(mask, src, dst):
    out = [[0.0] * dst for _ in range(dst)]
    f = src / dst
    for y in range(dst):
        y0, y1 = int(y * f), int((y + 1) * f)
        for x in range(dst):
            x0, x1 = int(x * f), int((x + 1) * f)
            acc = 0.0
            n = 0
            for yy in range(max(0, y0), min(src, y1)):
                row = mask[yy]
                for xx in range(max(0, x0), min(src, x1)):
                    acc += row[xx]
                    n += 1
            out[y][x] = acc / n if n else 0.0
    return out


if __name__ == '__main__':
    main()
