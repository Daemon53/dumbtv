package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/**
 * Background › Christmas: a wooden picture frame with a pine garland, a string of twinkling
 * lights and hanging ornaments, over a dimmed room with a fireplace whose flames move.
 *
 * Everything is drawn here; there are no image files. What does not move is painted once into two
 * bitmaps when the size is known (the room behind, the frame in front), so a frame only adds the
 * flames and the bulbs. Movement is kept slow and soft, a background rather than a show. It
 * animates at about 15 fps and only between start() and stop(), which
 * Home calls from onResume and onPause, so nothing runs while an app or input is on screen.
 */
final class Christmas extends View {
    private static final long FRAME_MS = 66;
    /** Warm fade over a fireplace video, so the real fire sits back behind the decorations. */
    static final int VIDEO_FADE = 0x9E1A0C06;
    private static final int[] BULBS = {0xFFFF4136, 0xFF2ECC40, 0xFFFFC233, 0xFF3D8BFF, 0xFFFFF1D0};
    private static final int[] ORNAMENTS = {0xFFD62A3A, 0xFFE3B23C, 0xFF2F9E55, 0xFF3F74CF};
    private static final int[] GREENS = {0xFF1E4D2B, 0xFF2E6B3A, 0xFF164022, 0xFF3A7D46, 0xFF25603A};
    private static final int[] FLAME_OUTER = {0xFFFFE08A, 0xFFFF8A1E, 0xCCD9361A, 0x00A0200A};
    private static final float[] FLAME_OUTER_AT = {0f, 0.3f, 0.7f, 1f};
    private static final int[] FLAME_INNER = {0xFFFFFBE6, 0xFFFFD45C, 0x00FF9A2A};
    private static final float[] FLAME_INNER_AT = {0f, 0.5f, 1f};

    private final Paint bitmaps = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final RectF firebox = new RectF();
    private final long start = SystemClock.uptimeMillis();
    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    private Bitmap back, front;
    private Bitmap[] glows, lit;
    private float bulbR, glowR;
    private float[] bulbX, bulbY, bulbPhase, bulbSpeed;
    private float[] flameX, flameW, flameH, phase1, phase2, phase3;
    private Shader fireGlow;
    private boolean running;
    /** Night mode: the fire burns low and the lights dim. */
    private boolean night;
    /** A real fireplace video plays underneath: draw only the frame and decorations over it. */
    private boolean overVideo;
    private Shader topScrim;

    Christmas(Context c) {
        super(c);
    }

    void start() {
        if (running) return;
        running = true;
        removeCallbacks(frame);
        post(frame);
    }

    void stop() {
        running = false;
        removeCallbacks(frame);
    }

    void setNight(boolean on) {
        if (night == on) return;
        night = on;
        invalidate();
    }

    void setOverVideo(boolean on) {
        if (overVideo == on) return;
        overVideo = on;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        if (w <= 0 || h <= 0) return;
        if (back != null) back.recycle();
        if (front != null) front.recycle();
        Random rnd = new Random(1225);
        int t = Math.round(w * 0.024f);
        back = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        room(new Canvas(back), w, h, rnd);
        front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(front);
        frame(c, w, h, t, rnd);
        garland(c, w, t, rnd);
        lights(c, w, h, t, rnd);
        ornaments(c, w, h, t);
        holly(c, t, t, 0);
        holly(c, w - t, t, 90);
        holly(c, w - t, h - t, 180);
        holly(c, t, h - t, 270);
        fire(rnd);
        topScrim = new LinearGradient(0, 0, 0, h * 0.34f, 0xA6000000, 0x00000000, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (back == null) return;
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        if (overVideo) {
            // The video shows through; only keep the clock and weather readable.
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(topScrim);
            c.drawRect(0, 0, getWidth(), getHeight() * 0.34f, paint);
            paint.setShader(null);
        } else {
            c.drawBitmap(back, 0, 0, bitmaps);
            drawFire(c, t);
        }
        c.drawBitmap(front, 0, 0, bitmaps);
        drawBulbs(c, t);
    }

    // ---- the room and the fireplace, painted once ---------------------------------------------

    private void room(Canvas c, int w, int h, Random rnd) {
        paint.setShader(new LinearGradient(0, 0, 0, h, new int[] {0xFF0C0605, 0xFF1E0F0A, 0xFF160B08},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setColor(0x0CFFE0B0);
        for (float x = w * 0.0125f; x < w; x += w / 32f) c.drawRect(x, 0, x + w * 0.004f, h, paint);

        // Brick surround.
        float left = w * 0.27f, right = w * 0.73f, top = h * 0.50f, row = h * 0.04f, brick = w * 0.05f;
        for (int r = 0; top + r * row < h; r++) {
            float y = top + r * row, off = (r % 2) * brick / 2;
            for (float x = left - off; x < right; x += brick) {
                paint.setColor(shade(0xFF552A1B, 0.8f + rnd.nextFloat() * 0.35f));
                c.drawRect(Math.max(left, x), y, Math.min(right, x + brick), y + row, paint);
            }
        }
        paint.setColor(0xFF2A130C);
        paint.setStrokeWidth(Math.max(2, h * 0.004f));
        for (int r = 0; top + r * row < h; r++) {
            float y = top + r * row, off = (r % 2) * brick / 2;
            c.drawLine(left, y, right, y, paint);
            for (float x = left - off; x < right; x += brick) {
                if (x > left) c.drawLine(x, y, x, Math.min(h, y + row), paint);
            }
        }

        // Mantel shelf.
        paint.setShader(new LinearGradient(0, h * 0.465f, 0, h * 0.505f, 0xFF7A4B2A, 0xFF3A2112,
                Shader.TileMode.CLAMP));
        c.drawRect(w * 0.235f, h * 0.465f, w * 0.765f, h * 0.505f, paint);
        paint.setShader(null);
        paint.setColor(0x50FFD9A0);
        c.drawRect(w * 0.235f, h * 0.465f, w * 0.765f, h * 0.468f, paint);

        stocking(c, w * 0.335f, h * 0.505f, w * 0.032f, 0xFFB3122A);
        stocking(c, w * 0.645f, h * 0.505f, w * 0.032f, 0xFF1E6B35);

        // Firebox and hearth.
        firebox.set(w * 0.37f, h * 0.60f, w * 0.63f, h * 0.95f);
        float arch = firebox.width() * 0.18f;
        path.reset();
        path.addRoundRect(firebox, new float[] {arch, arch, arch, arch, 0, 0, 0, 0}, Path.Direction.CW);
        paint.setShader(new LinearGradient(0, firebox.top, 0, firebox.bottom, 0xFF050202, 0xFF2A0D05,
                Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);
        paint.setColor(0xFF3B3430);
        c.drawRect(w * 0.25f, h * 0.95f, w * 0.75f, h, paint);
        paint.setColor(0x40FFFFFF);
        c.drawRect(w * 0.25f, h * 0.95f, w * 0.75f, h * 0.953f, paint);

        // Fade the fireplace into the background, darken the edges, keep the clock readable.
        c.drawColor(0x80140A07);
        paint.setShader(new RadialGradient(w / 2f, h * 0.55f, w * 0.75f, 0x00000000, 0xB0000000,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(new LinearGradient(0, 0, 0, h * 0.32f, 0x99000000, 0x00000000,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.32f, paint);
        paint.setShader(null);
    }

    private void stocking(Canvas c, float x, float y, float width, int color) {
        float leg = width * 2.6f;
        path.reset();
        path.moveTo(x, y);
        path.lineTo(x + width, y);
        path.lineTo(x + width, y + leg);
        path.quadTo(x + width, y + leg + width * 0.9f, x - width * 0.9f, y + leg + width * 0.55f);
        path.quadTo(x - width * 1.2f, y + leg - width * 0.15f, x, y + leg - width * 0.2f);
        path.close();
        paint.setColor(color);
        c.drawPath(path, paint);
        paint.setColor(0xFFF2EDE4);
        rect.set(x - width * 0.1f, y, x + width * 1.1f, y + width * 0.55f);
        c.drawRoundRect(rect, width * 0.15f, width * 0.15f, paint);
    }

    private void fire(Random rnd) {
        int n = 9;
        flameX = new float[n];
        flameW = new float[n];
        flameH = new float[n];
        phase1 = new float[n];
        phase2 = new float[n];
        phase3 = new float[n];
        float mid = (n - 1) / 2f;
        for (int i = 0; i < n; i++) {
            float edge = Math.abs(i - mid) / mid;
            flameX[i] = firebox.left + firebox.width() * (0.14f + 0.72f * i / (n - 1));
            flameW[i] = firebox.width() * (0.09f + rnd.nextFloat() * 0.04f);
            flameH[i] = firebox.height() * (0.38f + 0.32f * (1 - edge)) * (0.85f + rnd.nextFloat() * 0.3f);
            phase1[i] = rnd.nextFloat() * 6.28f;
            phase2[i] = rnd.nextFloat() * 6.28f;
            phase3[i] = rnd.nextFloat() * 6.28f;
        }
        fireGlow = new RadialGradient(firebox.centerX(), firebox.bottom - firebox.height() * 0.2f,
                firebox.width() * 0.9f, 0xFFFF7A22, 0x00FF5A10, Shader.TileMode.CLAMP);
    }

    // ---- the frame, garland, lights and ornaments, painted once -------------------------------

    private void frame(Canvas c, int w, int h, int t, Random rnd) {
        int[] wood = {0xFF8C5A33, 0xFF6B3F20, 0xFF432612};
        for (int side = 0; side < 4; side++) {
            path.reset();
            Shader grain;
            switch (side) {
                case 0:
                    path.moveTo(0, 0); path.lineTo(w, 0); path.lineTo(w - t, t); path.lineTo(t, t);
                    grain = new LinearGradient(0, 0, 0, t, wood, null, Shader.TileMode.CLAMP);
                    break;
                case 1:
                    path.moveTo(0, h); path.lineTo(w, h); path.lineTo(w - t, h - t); path.lineTo(t, h - t);
                    grain = new LinearGradient(0, h, 0, h - t, wood, null, Shader.TileMode.CLAMP);
                    break;
                case 2:
                    path.moveTo(0, 0); path.lineTo(t, t); path.lineTo(t, h - t); path.lineTo(0, h);
                    grain = new LinearGradient(0, 0, t, 0, wood, null, Shader.TileMode.CLAMP);
                    break;
                default:
                    path.moveTo(w, 0); path.lineTo(w, h); path.lineTo(w - t, h - t); path.lineTo(w - t, t);
                    grain = new LinearGradient(w, 0, w - t, 0, wood, null, Shader.TileMode.CLAMP);
            }
            path.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(grain);
            c.drawPath(path, paint);
            paint.setShader(null);

            // Wood grain: long wavy strokes along the board, and a few knots.
            c.save();
            c.clipPath(path);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            boolean across = side < 2;
            int length = across ? w : h;
            for (float off = 1; off < t; off += 2.5f + rnd.nextFloat() * 2) {
                paint.setColor(rnd.nextBoolean() ? 0x40301505 : 0x16FFD9A0);
                Path line = new Path();
                float base = side == 1 ? h - off : side == 3 ? w - off : off;
                float step = length / 10f, amp = 0.8f + rnd.nextFloat() * 2f;
                if (across) line.moveTo(0, base); else line.moveTo(base, 0);
                for (float s = 0; s < length; s += step) {
                    float wobble = (rnd.nextFloat() - 0.5f) * 2 * amp;
                    if (across) line.quadTo(s + step / 2, base + wobble, s + step, base);
                    else line.quadTo(base + wobble, s + step / 2, base, s + step);
                }
                c.drawPath(line, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x38200C02);
            for (int k = 0; k < 3; k++) {
                float along = length * (0.15f + rnd.nextFloat() * 0.7f), into = t * (0.3f + rnd.nextFloat() * 0.4f);
                float kx = across ? along : side == 3 ? w - into : into;
                float ky = across ? (side == 1 ? h - into : into) : along;
                rect.set(kx - t * 0.35f, ky - t * 0.12f, kx + t * 0.35f, ky + t * 0.12f);
                if (!across) rect.set(kx - t * 0.12f, ky - t * 0.35f, kx + t * 0.12f, ky + t * 0.35f);
                c.drawOval(rect, paint);
            }
            c.restore();
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0x60000000);
        c.drawLine(0, 0, t, t, paint);
        c.drawLine(w, 0, w - t, t, paint);
        c.drawLine(0, h, t, h - t, paint);
        c.drawLine(w, h, w - t, h - t, paint);
        paint.setColor(0x50FFE3B5);
        c.drawRect(1, 1, w - 1, h - 1, paint);
        paint.setStrokeWidth(3);
        paint.setColor(0xFFC9A24A);
        c.drawRect(t - 2, t - 2, w - t + 2, h - t + 2, paint);
        paint.setStrokeWidth(4);
        paint.setColor(0xC0170B04);
        c.drawRect(t + 1, t + 1, w - t - 1, h - t - 1, paint);
        paint.setStyle(Paint.Style.FILL);

        // The frame's shadow on the picture.
        float s = t * 0.6f;
        shadow(c, t, t, w - t, t + s, 0, t, 0, t + s);
        shadow(c, t, h - t - s, w - t, h - t, 0, h - t, 0, h - t - s);
        shadow(c, t, t, t + s, h - t, t, 0, t + s, 0);
        shadow(c, w - t - s, t, w - t, h - t, w - t, 0, w - t - s, 0);
    }

    private void shadow(Canvas c, float l, float tp, float r, float b, float x0, float y0, float x1, float y1) {
        paint.setShader(new LinearGradient(x0, y0, x1, y1, 0x90000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawRect(l, tp, r, b, paint);
        paint.setShader(null);
    }

    private void garland(Canvas c, int w, int t, Random rnd) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2.2f);
        float y0 = t + 2;
        for (float x = t - 6; x < w - t + 6; x += 2.2f) {
            for (int j = 0; j < 3; j++) {
                double angle = Math.toRadians(15 + rnd.nextFloat() * 150);
                float len = t * (0.25f + rnd.nextFloat() * 0.35f);
                float sx = x, sy = y0 + rnd.nextFloat() * t * 0.15f;
                paint.setColor(GREENS[rnd.nextInt(GREENS.length)]);
                c.drawLine(sx, sy, sx + (float) Math.cos(angle) * len, sy + (float) Math.sin(angle) * len, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        for (float x = t + w / 44f; x < w - t; x += w / 22f) {
            paint.setColor(0xFFD4A84A);
            c.drawCircle(x, y0 + t * 0.3f, t * 0.09f, paint);
            paint.setColor(0x90FFF2C0);
            c.drawCircle(x - t * 0.03f, y0 + t * 0.27f, t * 0.03f, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void lights(Canvas c, int w, int h, int t, Random rnd) {
        int spans = 8, perSpan = 4, n = spans * perSpan;
        float y = t + t * 0.35f, sag = h * 0.028f, span = (w - 2f * t) / spans;
        bulbR = h * 0.0085f;
        bulbX = new float[n];
        bulbY = new float[n];
        bulbPhase = new float[n];
        bulbSpeed = new float[n];

        path.reset();
        path.moveTo(t, y);
        for (int s = 0; s < spans; s++) {
            float ax = t + s * span, bx = ax + span, cx = ax + span / 2, cy = y + 2 * sag;
            path.quadTo(cx, cy, bx, y);
            for (int k = 0; k < perSpan; k++) {
                float u = (k + 0.5f) / perSpan, v = 1 - u;
                int i = s * perSpan + k;
                bulbX[i] = v * v * ax + 2 * v * u * cx + u * u * bx;
                bulbY[i] = v * v * y + 2 * v * u * cy + u * u * y + bulbR * 1.3f;
                bulbPhase[i] = rnd.nextFloat() * 6.28f;
                bulbSpeed[i] = 0.25f + rnd.nextFloat() * 0.45f;
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.5f);
        paint.setColor(0xFF0B1E10);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < n; i++) {
            paint.setColor(0xFF173A1F);
            c.drawRect(bulbX[i] - bulbR * 0.55f, bulbY[i] - bulbR * 1.9f, bulbX[i] + bulbR * 0.55f,
                    bulbY[i] - bulbR * 0.9f, paint);
            paint.setColor(shade(BULBS[i % BULBS.length], 0.45f));
            bulb(c, bulbX[i], bulbY[i]);
        }

        glowR = bulbR * 4.5f;
        glows = new Bitmap[BULBS.length];
        int size = Math.max(2, Math.round(glowR * 2));
        for (int k = 0; k < BULBS.length; k++) {
            glows[k] = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            int color = BULBS[k];
            g.setShader(new RadialGradient(size / 2f, size / 2f, size / 2f,
                    new int[] {(color & 0x00FFFFFF) | 0xB0000000, (color & 0x00FFFFFF) | 0x30000000, color & 0x00FFFFFF},
                    new float[] {0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
            new Canvas(glows[k]).drawCircle(size / 2f, size / 2f, size / 2f, g);
        }

        // Lit glass: a near-white filament glow inside, the colour through the glass, darker at
        // the edge, and a small reflection.
        int bw = Math.max(2, Math.round(bulbR * 1.6f)), bh = Math.max(2, Math.round(bulbR * 2.4f));
        lit = new Bitmap[BULBS.length];
        for (int k = 0; k < BULBS.length; k++) {
            lit[k] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas s = new Canvas(lit[k]);
            Paint g = new Paint(Paint.ANTI_ALIAS_FLAG);
            int color = BULBS[k];
            g.setShader(new RadialGradient(bw / 2f, bh * 0.55f, bh * 0.55f,
                    new int[] {0xFFFFFBEF, shade(color, 1.25f), color, shade(color, 0.55f)},
                    new float[] {0f, 0.25f, 0.65f, 1f}, Shader.TileMode.CLAMP));
            s.drawOval(new RectF(0, 0, bw, bh), g);
            g.setShader(null);
            g.setColor(0x99FFFFFF);
            s.drawOval(new RectF(bw * 0.22f, bh * 0.12f, bw * 0.45f, bh * 0.38f), g);
        }
    }

    private void bulb(Canvas c, float x, float y) {
        rect.set(x - bulbR * 0.75f, y - bulbR * 1.1f, x + bulbR * 0.75f, y + bulbR * 1.1f);
        c.drawOval(rect, paint);
    }

    /** Hung in the gap between the weather and the Search button. */
    private void ornaments(Canvas c, int w, int h, int t) {
        float[] xs = {0.548f, 0.587f, 0.626f};
        float[] drop = {0.11f, 0.16f, 0.09f};
        float[] size = {0.034f, 0.029f, 0.036f};
        for (int i = 0; i < xs.length; i++) {
            float x = w * xs[i], r = h * size[i], cy = t + h * drop[i];
            int base = ORNAMENTS[i % ORNAMENTS.length];

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(0xFFB89B5E);
            c.drawLine(x, t, x, cy - r * 1.3f, paint);
            c.drawCircle(x, cy - r * 1.38f, r * 0.1f, paint);
            paint.setStyle(Paint.Style.FILL);

            paint.setShader(new LinearGradient(x - r * 0.28f, 0, x + r * 0.28f, 0,
                    new int[] {0xFF8C6D2C, 0xFFF0D58A, 0xFF8C6D2C}, null, Shader.TileMode.CLAMP));
            c.drawRect(x - r * 0.28f, cy - r * 1.3f, x + r * 0.28f, cy - r * 0.92f, paint);

            paint.setShader(new RadialGradient(x - r * 0.35f, cy - r * 0.4f, r * 1.5f,
                    new int[] {shade(base, 1.7f), base, shade(base, 0.35f)}, new float[] {0f, 0.45f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawCircle(x, cy, r, paint);
            paint.setShader(null);

            c.save();
            path.reset();
            path.addCircle(x, cy, r, Path.Direction.CW);
            c.clipPath(path);
            if (i % 2 == 0) {
                paint.setColor(0x90FFF6E0);
                c.drawRect(x - r, cy - r * 0.12f, x + r, cy + r * 0.12f, paint);
            } else {
                paint.setColor(0xD0F5DC8A);
                for (int k = -2; k <= 2; k++) c.drawCircle(x + k * r * 0.38f, cy + r * 0.2f, r * 0.08f, paint);
            }
            c.restore();

            // Glass: a warm reflection of the fire low on the right, a soft window highlight up left,
            // and a darker rim, so the ball reads as round and shiny rather than flat.
            paint.setShader(new RadialGradient(x + r * 0.45f, cy + r * 0.55f, r * 0.8f, 0x66FFB060, 0x00FFB060,
                    Shader.TileMode.CLAMP));
            c.drawCircle(x, cy, r, paint);
            paint.setShader(new RadialGradient(x - r * 0.38f, cy - r * 0.42f, r * 0.42f,
                    new int[] {0xE6FFFFFF, 0x55FFFFFF, 0x00FFFFFF}, new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(x - r * 0.38f, cy - r * 0.42f, r * 0.42f, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1.5f, r * 0.06f));
            paint.setColor(0x55000000);
            c.drawCircle(x, cy, r - r * 0.03f, paint);
            paint.setStyle(Paint.Style.FILL);
            // Ridges on the cap.
            paint.setColor(0x60503A10);
            for (int k = 1; k < 4; k++) {
                float rx = x - r * 0.28f + k * r * 0.14f;
                c.drawRect(rx, cy - r * 1.3f, rx + Math.max(1, r * 0.03f), cy - r * 0.92f, paint);
            }
        }
    }

    /** Three holly leaves and berries over a corner of the frame, turned by {@code degrees}. */
    private void holly(Canvas c, float x, float y, float degrees) {
        float len = getWidth() * 0.024f * 1.6f;
        c.save();
        c.translate(x, y);
        c.rotate(degrees);
        for (float a : new float[] {-15, 45, 105}) {
            c.save();
            c.rotate(a);
            path.reset();
            path.moveTo(0, 0);
            path.quadTo(len * 0.3f, -len * 0.32f, len * 0.55f, -len * 0.18f);
            path.quadTo(len * 0.75f, -len * 0.35f, len, 0);
            path.quadTo(len * 0.75f, len * 0.35f, len * 0.55f, len * 0.18f);
            path.quadTo(len * 0.3f, len * 0.32f, 0, 0);
            paint.setShader(new LinearGradient(0, -len * 0.3f, 0, len * 0.3f, 0xFF2E7A3E, 0xFF123D1C,
                    Shader.TileMode.CLAMP));
            c.drawPath(path, paint);
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f);
            paint.setColor(0x8040A060);
            c.drawLine(0, 0, len * 0.9f, 0, paint);
            paint.setStyle(Paint.Style.FILL);
            c.restore();
        }
        float br = len * 0.11f;
        float[][] berries = {{br * 0.6f, br * 1.2f}, {br * 1.9f, br * 0.4f}, {br * 1.6f, br * 2.0f}};
        for (float[] b : berries) {
            paint.setShader(new RadialGradient(b[0] - br * 0.3f, b[1] - br * 0.3f, br * 1.3f,
                    new int[] {0xFFFF5A5F, 0xFFC21E2C, 0xFF5E0A12}, new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(b[0], b[1], br, paint);
            paint.setShader(null);
            paint.setColor(0xB0FFFFFF);
            c.drawCircle(b[0] - br * 0.35f, b[1] - br * 0.35f, br * 0.22f, paint);
        }
        c.restore();
    }

    // ---- what moves ----------------------------------------------------------------------------

    private void drawFire(Canvas c, float t) {
        float flicker = 0.88f + 0.08f * (float) Math.sin(t * 2.3) + 0.04f * (float) Math.sin(t * 4.1);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(fireGlow);
        paint.setAlpha((int) ((night ? 40 : 70) * flicker));
        c.drawCircle(firebox.centerX(), firebox.bottom - firebox.height() * 0.2f, firebox.width() * 0.9f, paint);

        float base = firebox.bottom - firebox.height() * 0.10f;
        for (int i = 0; i < flameX.length; i++) {
            float height = flameH[i] * (night ? 0.5f : 1f) * (0.8f + 0.10f * (float) Math.sin(t * 1.4f + phase1[i])
                    + 0.05f * (float) Math.sin(t * 2.9f + phase2[i]));
            float sway = firebox.width() * (0.014f * (float) Math.sin(t * 1.1f + phase3[i])
                    + 0.006f * (float) Math.sin(t * 2.3f + phase1[i]));
            tongue(c, flameX[i], base, flameW[i], height, sway, FLAME_OUTER, FLAME_OUTER_AT, 90);
            tongue(c, flameX[i] + sway * 0.2f, base, flameW[i] * 0.55f, height * 0.6f, sway * 0.6f,
                    FLAME_INNER, FLAME_INNER_AT, 100);
        }
        paint.setShader(null);

        // Two logs over the flames' roots, with embers along their tops.
        float cx = firebox.centerX(), cy = base + firebox.height() * 0.04f;
        float length = firebox.width() * 0.62f, thick = firebox.height() * 0.09f;
        for (int k = 0; k < 2; k++) {
            c.save();
            c.rotate(k == 0 ? -6 : 7, cx, cy);
            rect.set(cx - length / 2, cy - thick / 2, cx + length / 2, cy + thick / 2);
            paint.setColor(0xD0331D10);
            c.drawRoundRect(rect, thick / 2, thick / 2, paint);
            paint.setColor(0xFFFF6A1A);
            paint.setAlpha((int) (110 * flicker));
            c.drawRect(rect.left + thick, rect.top, rect.right - thick, rect.top + 3, paint);
            c.restore();
        }
        paint.setAlpha(255);
    }

    private void tongue(Canvas c, float x, float base, float width, float height, float sway,
            int[] colors, float[] stops, int alpha) {
        float half = width / 2;
        path.reset();
        path.moveTo(x - half, base);
        path.cubicTo(x - half, base - height * 0.45f, x - half * 0.25f + sway * 0.5f, base - height * 0.7f,
                x + sway, base - height);
        path.cubicTo(x + half * 0.25f + sway * 0.5f, base - height * 0.7f, x + half, base - height * 0.45f,
                x + half, base);
        path.close();
        paint.setShader(new LinearGradient(0, base, 0, base - height, colors, stops, Shader.TileMode.CLAMP));
        paint.setAlpha(alpha);
        c.drawPath(path, paint);
    }

    private void drawBulbs(Canvas c, float t) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < bulbX.length; i++) {
            float lit = (night ? 0.5f : 1f) * (0.8f + 0.2f * (float) Math.sin(t * bulbSpeed[i] + bulbPhase[i]));
            int k = i % BULBS.length;
            bitmaps.setAlpha((int) (255 * lit));
            c.drawBitmap(glows[k], bulbX[i] - glowR, bulbY[i] - glowR, bitmaps);
            bitmaps.setAlpha((int) (120 + 135 * lit));
            c.drawBitmap(this.lit[k], bulbX[i] - this.lit[k].getWidth() / 2f, bulbY[i] - this.lit[k].getHeight() / 2f, bitmaps);
        }
        bitmaps.setAlpha(255);
    }

    private static int shade(int color, float f) {
        return Color.argb(Color.alpha(color), clamp(Color.red(color) * f), clamp(Color.green(color) * f),
                clamp(Color.blue(color) * f));
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }
}
