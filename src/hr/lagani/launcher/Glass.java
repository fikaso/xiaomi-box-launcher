package hr.lagani.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/**
 * Apple TV style "glass" without real-time blur (the box is too weak for that):
 * the background is painted only once, as soft color blobs that look like a blurred photo,
 * and cards and buttons are translucent surfaces with a thin light edge.
 */
final class Glass {

    static final int TEXT = Color.WHITE;
    static final int TEXT_DIM = Color.argb(150, 255, 255, 255);
    static final int TEXT_ON_FOCUS = Color.rgb(18, 20, 28);

    private static Bitmap background;

    private Glass() {
    }

    /** The background is painted at low resolution and stretched by bilinear scaling, so it stays soft. */
    static Drawable background(Context context) {
        if (background == null) background = paintBackground(384, 216);
        BitmapDrawable d = new BitmapDrawable(context.getResources(), background);
        d.setFilterBitmap(true);
        return d;
    }

    private static Bitmap paintBackground(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(Color.rgb(8, 10, 18));
        blob(c, w * 0.12f, h * 0.05f, w * 0.55f, Color.argb(210, 36, 72, 168));  // blue
        blob(c, w * 0.88f, h * 0.15f, w * 0.50f, Color.argb(170, 112, 52, 156)); // purple
        blob(c, w * 0.68f, h * 1.00f, w * 0.62f, Color.argb(150, 18, 118, 140)); // turquoise
        blob(c, w * 0.05f, h * 0.95f, w * 0.40f, Color.argb(110, 168, 84, 62));  // warm
        // Darker toward the bottom, so labels stay readable.
        Paint shade = new Paint();
        shade.setShader(new LinearGradient(0, 0, 0, h,
                Color.argb(30, 0, 0, 0), Color.argb(150, 0, 0, 0), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, shade);
        return b;
    }

    private static void blob(Canvas c, float cx, float cy, float r, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(cx, cy, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, r, p);
    }

    /**
     * Banner with rounded corners, drawn once into a bitmap. On the box, outline clipping (clipToOutline)
     * of an enlarged card clips to the old size, so the corners are done here.
     */
    static Drawable roundedBanner(Context context, Drawable art, int w, int h, float radius) {
        Bitmap src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(src);
        int iw = art.getIntrinsicWidth(), ih = art.getIntrinsicHeight();
        if (iw > 0 && ih > 0) {
            // Like CENTER_CROP: fill the card, crop the excess equally on both sides.
            float scale = Math.max(w / (float) iw, h / (float) ih);
            int dw = Math.round(iw * scale), dh = Math.round(ih * scale);
            art.setBounds((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
        } else {
            art.setBounds(0, 0, w, h);
        }
        art.draw(c);

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new android.graphics.BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(0, 0, w, h, radius, radius, p);
        src.recycle();
        return new BitmapDrawable(context.getResources(), out);
    }

    /** Translucent surface with a thin edge. */
    static GradientDrawable pane(float radius, int fillAlpha, int edgeAlpha, int edgePx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.argb(fillAlpha, 255, 255, 255));
        d.setCornerRadius(radius);
        if (edgePx > 0) d.setStroke(edgePx, Color.argb(edgeAlpha, 255, 255, 255));
        return d;
    }

    /** Sheen over the focused card: light from above that fades toward the middle. */
    static GradientDrawable sheen(float radius, int edgePx) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{
                Color.argb(80, 255, 255, 255), Color.argb(14, 255, 255, 255), Color.argb(0, 255, 255, 255)});
        d.setCornerRadius(radius);
        d.setStroke(edgePx, Color.argb(150, 255, 255, 255));
        return d;
    }

    /** Button as a glass "pill"; when focused it turns white with dark text, as on Apple TV. */
    static Drawable pill(float radius, int edgePx) {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.argb(240, 255, 255, 255));
        on.setCornerRadius(radius);
        d.addState(new int[]{android.R.attr.state_focused}, on);
        d.addState(new int[]{}, pane(radius, 30, 46, edgePx));
        return d;
    }

    /** Temperature color as in Apple Weather: blue for cold, green-yellow for mild, orange-red for hot. */
    static int tempColor(int t) {
        int[][] stops = {{-5, 0xFF5AC8FA}, {5, 0xFF64D2C8}, {15, 0xFFB4DC50}, {22, 0xFFFFCC00}, {30, 0xFFFF9500}, {38, 0xFFFF3B30}};
        if (t <= stops[0][0]) return stops[0][1];
        for (int i = 1; i < stops.length; i++) {
            if (t <= stops[i][0]) {
                float f = (t - stops[i - 1][0]) / (float) (stops[i][0] - stops[i - 1][0]);
                return blend(stops[i - 1][1], stops[i][1], f);
            }
        }
        return stops[stops.length - 1][1];
    }

    private static int blend(int a, int b, float f) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
    }

    /**
     * "5° ━━ 16°" as in Apple Weather: the low dimmed on the left, the high white and emphasized on the right.
     * The bar is a gray track for the range of all shown days ({@code scaleMin}–{@code scaleMax}), and
     * the colored part shows where that day falls, so warmer and colder days stand out at a glance.
     */
    static CharSequence range(Context context, int low, int high, int scaleMin, int scaleMax,
                              int barWidthPx, int barHeightPx) {
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder();
        s.append(low + "°");
        s.setSpan(new android.text.style.ForegroundColorSpan(TEXT_DIM), 0, s.length(), 0);
        s.append(" ");
        int at = s.length();
        s.append("■");
        Drawable bar = new RangeBar(low, high, Math.min(scaleMin, low), Math.max(scaleMax, high), barHeightPx / 2f);
        bar.setBounds(0, 0, barWidthPx, barHeightPx);
        s.setSpan(new android.text.style.ImageSpan(bar, android.text.style.DynamicDrawableSpan.ALIGN_BASELINE) {
            @Override
            public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                             int top, int y, int bottom, Paint paint) {
                Drawable d = getDrawable();
                canvas.save();
                // Vertically centered on the x-height, not on the baseline.
                canvas.translate(x, y - paint.getTextSize() * 0.36f - d.getBounds().height() / 2f);
                d.draw(canvas);
                canvas.restore();
            }
        }, at, at + 1, 0);
        s.append(" ");
        int hi = s.length();
        s.append(high + "°");
        s.setSpan(new android.text.style.TypefaceSpan("sans-serif-medium"), hi, s.length(), 0);
        s.setSpan(new android.text.style.RelativeSizeSpan(1.12f), hi, s.length(), 0);
        return s;
    }

    /** Full-width gray track with a colored part on it from the day's low to its high temperature. */
    private static final class RangeBar extends Drawable {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int low, high, min, max;
        private final float radius;

        RangeBar(int low, int high, int min, int max, float radius) {
            this.low = low;
            this.high = high;
            this.min = min;
            this.max = max;
            this.radius = radius;
            track.setColor(Color.argb(45, 255, 255, 255));
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, track);
            float span = Math.max(1, max - min);
            float from = b.left + b.width() * (low - min) / span;
            float to = b.left + b.width() * (high - min) / span;
            if (to - from < b.height()) to = Math.min(b.right, from + b.height()); // at least a dot
            fill.setShader(new LinearGradient(from, 0, to, 0, tempColor(low), tempColor(high), Shader.TileMode.CLAMP));
            canvas.drawRoundRect(from, b.top, to, b.bottom, radius, radius, fill);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Like pill, but with no surface when not focused (for list rows). */
    static Drawable focusPill(float radius) {
        StateListDrawable d = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.argb(240, 255, 255, 255));
        on.setCornerRadius(radius);
        d.addState(new int[]{android.R.attr.state_focused}, on);
        return d;
    }

    static ColorStateList pillText() {
        return new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{TEXT_ON_FOCUS, TEXT});
    }
}
