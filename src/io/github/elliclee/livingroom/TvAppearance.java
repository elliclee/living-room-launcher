package io.github.elliclee.livingroom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.List;

/** tvOS-style tokens and native materials shared by the home screen, panels and app cards. */
final class TvAppearance {
    static final int INK = 0xff1d1d1f, TEXT = 0xfff5f5f7, MUTED = 0xc8ebebf5, FOCUS = 0xffffffff;
    static final int REST = 0x24ffffff, TILE = 0x1cffffff, FIELD = 0x2effffff, PLACEHOLDER = 0x26ffffff;
    /** Clear glass lets the image lead (dock, clock); regular glass carries reading content (panels). */
    static final int CLEAR = 0, REGULAR = 1;

    private TvAppearance() { }

    static Typeface face(int weight) {
        if (Build.VERSION.SDK_INT >= 28) return Typeface.create(Typeface.SANS_SERIF, weight, false);
        return Typeface.create(weight >= 500 ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
    }

    static int lerp(int from, int to, float f) {
        f = Math.max(0, Math.min(1, f));
        return Color.argb(Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * f),
            Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * f),
            Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * f),
            Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f));
    }

    /** A small blurred, slightly more saturated copy; drawn scaled up it serves every glass layer. */
    static Bitmap frost(Bitmap source) {
        int w = 384, h = Math.max(1, Math.round(384f * source.getHeight() / source.getWidth()));
        Bitmap middle = Bitmap.createScaledBitmap(source, w * 2, h * 2, true);
        Bitmap small = Bitmap.createScaledBitmap(middle, w, h, true);
        if (middle != source && middle != small) middle.recycle();
        int[] pixels = new int[w * h];
        small.getPixels(pixels, 0, w, 0, 0, w, h);
        if (small != source) small.recycle();
        blur(pixels, w, h, 5);
        saturate(pixels, 1.35f);
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        result.setPixels(pixels, 0, w, 0, 0, w, h);
        return result;
    }

    /** Three separable box passes approximate a Gaussian blur on opaque pixels. */
    static void blur(int[] pixels, int w, int h, int radius) {
        int[] swap = new int[pixels.length];
        for (int pass = 0; pass < 3; pass++) {
            blurRows(pixels, swap, w, h, radius);
            blurRows(swap, pixels, h, w, radius);
        }
    }

    private static void blurRows(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w, a = 0, red = 0, green = 0, blue = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + Math.max(0, Math.min(w - 1, i))];
                a += c >>> 24; red += (c >> 16) & 255; green += (c >> 8) & 255; blue += c & 255;
            }
            for (int x = 0; x < w; x++) {
                out[x * h + y] = ((a / div) << 24) | ((red / div) << 16) | ((green / div) << 8) | (blue / div);
                int add = in[row + Math.min(w - 1, x + r + 1)], sub = in[row + Math.max(0, x - r)];
                a += (add >>> 24) - (sub >>> 24);
                red += ((add >> 16) & 255) - ((sub >> 16) & 255);
                green += ((add >> 8) & 255) - ((sub >> 8) & 255);
                blue += (add & 255) - (sub & 255);
            }
        }
    }

    private static void saturate(int[] pixels, float amount) {
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i], r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
            float l = .2126f * r + .7152f * g + .0722f * b;
            pixels[i] = (c & 0xff000000) | (clamp(l + (r - l) * amount) << 16) | (clamp(l + (g - l) * amount) << 8) | clamp(l + (b - l) * amount);
        }
    }

    private static int clamp(float value) { return Math.max(0, Math.min(255, Math.round(value))); }

    /** Full-screen backdrop. Owns the displayed image, its frosted copy and every glass that samples it. */
    static final class Wallpaper extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint scrim = new Paint();
        private final RectF imageRect = new RectF(), frostRect = new RectF();
        private final List<View> samplers = new ArrayList<>();
        private Bitmap bitmap, frosted;
        private Shader top, bottom, title;
        private float expanded, titleScrim;

        Wallpaper(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        void image(Bitmap value, Bitmap frost) {
            bitmap = value; frosted = frost; place(); invalidate();
            for (View sampler : samplers) sampler.invalidate();
        }
        void expanded(float value) { if (expanded != value) { expanded = value; invalidate(); } }
        void titleScrim(float value) { if (titleScrim != value) { titleScrim = value; invalidate(); } }

        private void place() {
            cover(bitmap, imageRect);
            cover(frosted, frostRect);
        }
        private void cover(Bitmap image, RectF out) {
            int w = getWidth(), h = getHeight();
            if (image == null || w == 0 || h == 0) { out.setEmpty(); return; }
            float scale = Math.max(w / (float) image.getWidth(), h / (float) image.getHeight());
            float dw = image.getWidth() * scale, dh = image.getHeight() * scale;
            out.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            place();
            top = new LinearGradient(0, 0, 0, h * .34f, 0x42000000, 0x00000000, Shader.TileMode.CLAMP);
            bottom = new LinearGradient(0, h * .5f, 0, h, 0x00000000, 0x6b000000, Shader.TileMode.CLAMP);
            title = new RadialGradient(w * .16f, h * .27f, w * .56f, 0x80000000, 0x00000000, Shader.TileMode.CLAMP);
        }

        /** The image glass samples (frosted when ready) and where it sits in screen coordinates. */
        Bitmap backdrop() { return frosted != null && !frosted.isRecycled() ? frosted : bitmap != null && !bitmap.isRecycled() ? bitmap : null; }
        RectF backdropRect() { return frosted != null && !frosted.isRecycled() ? frostRect : imageRect; }
        private void drawImage(Canvas canvas) {
            if (bitmap != null && !bitmap.isRecycled()) { canvas.drawBitmap(bitmap, null, imageRect, paint); return; }
            paint.setShader(new LinearGradient(0, 0, getWidth(), getHeight(), new int[]{0xff31435a, 0xff151d2a, 0xff090d14}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint); paint.setShader(null);
        }
        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            drawImage(canvas);
            if (expanded > 0 && frosted != null && !frosted.isRecycled()) {
                paint.setAlpha(Math.round(255 * expanded)); canvas.drawBitmap(frosted, null, frostRect, paint); paint.setAlpha(255);
            }
            scrim.setShader(top); canvas.drawRect(0, 0, w, h * .34f, scrim);
            scrim.setShader(bottom); canvas.drawRect(0, h * .5f, w, h, scrim);
            if (titleScrim > 0) { scrim.setShader(title); scrim.setAlpha(Math.round(255 * titleScrim)); canvas.drawRect(0, 0, w, h, scrim); scrim.setAlpha(255); }
            scrim.setShader(null);
            if (expanded > 0) canvas.drawColor(Color.argb(Math.round(92 * expanded), 0, 0, 0));
        }
    }

    /**
     * Glass that samples the frosted wallpaper at its own screen position, with sheen and a rim light.
     * Every layer is drawn as a rounded shape, so focus scaling never depends on outline clipping
     * (the TCL GPU renders scaled outline clips with cut corners).
     */
    static final class Glass extends FrameLayout {
        private final Wallpaper wallpaper;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final int[] here = new int[2], there = new int[2];
        private final RectF bounds = new RectF(), edge = new RectF();
        private final android.graphics.Matrix place = new android.graphics.Matrix();
        private final int style;
        private final float line;
        private float radius;
        private Shader sheen, rim;
        private Bitmap sampled;
        private android.graphics.BitmapShader backdrop;

        Glass(Context c, Wallpaper source, float cornerRadius, float rimWidth, int style) {
            super(c); wallpaper = source; radius = cornerRadius; line = Math.max(1f, rimWidth); this.style = style;
            setWillNotDraw(false); setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline out) { out.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius(v)); }
            });
        }
        /** Scrolling content needs the outline clip; focusable modules draw everything rounded and must not use it. */
        Glass clipContent(boolean value) { setClipToOutline(value); return this; }
        private float radius(View v) { return radius < 0 ? Math.min(v.getWidth(), v.getHeight()) / 2f : radius; }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); wallpaper.samplers.add(this); }
        @Override protected void onDetachedFromWindow() { wallpaper.samplers.remove(this); super.onDetachedFromWindow(); }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            sheen = new LinearGradient(0, 0, 0, h * .6f, style == REGULAR ? 0x14ffffff : 0x1fffffff, 0x00ffffff, Shader.TileMode.CLAMP);
            rim = new LinearGradient(0, 0, 0, h, new int[]{style == REGULAR ? 0x52ffffff : 0x75ffffff, 0x14ffffff, 0x24ffffff}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            float r = radius(this);
            bounds.set(0, 0, w, h);
            Bitmap source = wallpaper.backdrop();
            if (source != null) {
                if (source != sampled) { sampled = source; backdrop = new android.graphics.BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); }
                // Map the screen-space backdrop into this view's local, possibly scaled, coordinates.
                getLocationOnScreen(here); wallpaper.getLocationOnScreen(there);
                RectF dest = wallpaper.backdropRect();
                float sx = getScaleX() == 0 ? 1 : getScaleX(), sy = getScaleY() == 0 ? 1 : getScaleY();
                place.setScale(dest.width() / source.getWidth() / sx, dest.height() / source.getHeight() / sy);
                place.postTranslate((dest.left + there[0] - here[0]) / sx, (dest.top + there[1] - here[1]) / sy);
                backdrop.setLocalMatrix(place);
                // Shader draws inherit the paint alpha: reset it so the backdrop is fully opaque every frame.
                paint.setColor(0xff000000); paint.setShader(backdrop); canvas.drawRoundRect(bounds, r, r, paint); paint.setShader(null);
            } else { paint.setColor(0xff1b2433); canvas.drawRoundRect(bounds, r, r, paint); }
            paint.setStyle(Paint.Style.FILL); paint.setColor(style == REGULAR ? 0x9e17191d : 0x33101318); canvas.drawRoundRect(bounds, r, r, paint);
            paint.setColor(0xff000000); paint.setShader(sheen); canvas.drawRoundRect(bounds, r, r, paint);
            float inset = line / 2;
            edge.set(inset, inset, w - inset, h - inset);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(line); paint.setShader(rim);
            canvas.drawRoundRect(edge, r - inset, r - inset, paint);
            paint.setShader(null); paint.setStyle(Paint.Style.FILL);
        }
    }

    /** Soft drop shadow laid out with its blur spread around the card; all cards of one size share a mask. */
    static final class Shadow extends View {
        private static Bitmap shared;
        private static int sharedKey;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final float radius;
        private final int spread;
        private Bitmap mask;

        Shadow(Context c, float cornerRadius, int spread) {
            super(c); radius = cornerRadius; this.spread = spread;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); paint.setColor(0xff000000);
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            if (w <= 2 * spread || h <= 2 * spread) return;
            int key = (w * 31 + h) * 31 + spread;
            synchronized (Shadow.class) {
                if (shared == null || sharedKey != key) {
                    Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
                    Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG);
                    blur.setMaskFilter(new BlurMaskFilter(spread * .55f, BlurMaskFilter.Blur.NORMAL));
                    new Canvas(bitmap).drawRoundRect(spread, spread, w - spread, h - spread, radius, radius, blur);
                    shared = bitmap; sharedKey = key;
                }
                mask = shared;
            }
        }
        @Override protected void onDraw(Canvas canvas) { if (mask != null) canvas.drawBitmap(mask, 0, 0, paint); }
    }

    /** One app card: the rendered artwork, a resting sheen, focus glare and a specular rim, rounded while drawing. */
    static final class Artwork extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF frame = new RectF(), edge = new RectF();
        private final android.graphics.Matrix fit = new android.graphics.Matrix();
        private Bitmap bitmap;
        private Shader sheen, rim;
        private float radius, focus, glare = .5f;

        Artwork(Context c, float cornerRadius) {
            super(c); radius = cornerRadius;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        void image(Bitmap value) { if (bitmap != value) { bitmap = value; shade(); invalidate(); } }
        void focus(float value, float glareX) { focus = value; glare = glareX; invalidate(); }
        private void shade() {
            if (bitmap == null || bitmap.isRecycled() || getWidth() == 0) { image.setShader(null); return; }
            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            fit.setScale(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight());
            shader.setLocalMatrix(fit); image.setShader(shader);
        }
        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            frame.set(0, 0, w, h); shade();
            sheen = new LinearGradient(0, 0, 0, h * .55f, 0x1effffff, 0x00ffffff, Shader.TileMode.CLAMP);
            rim = new LinearGradient(0, 0, 0, h, 0xffffffff, 0x33ffffff, Shader.TileMode.CLAMP);
        }
        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (image.getShader() != null) canvas.drawRoundRect(frame, radius, radius, image);
            else { paint.setColor(PLACEHOLDER); canvas.drawRoundRect(frame, radius, radius, paint); }
            paint.setShader(sheen); canvas.drawRoundRect(frame, radius, radius, paint);
            if (focus > 0) {
                paint.setShader(new RadialGradient(w * glare, -h * .25f, w * 1.05f, 0x47ffffff, 0x00ffffff, Shader.TileMode.CLAMP));
                paint.setAlpha(Math.round(255 * focus)); canvas.drawRoundRect(frame, radius, radius, paint); paint.setAlpha(255);
            }
            float stroke = Math.max(1.2f, w / 200f), inset = stroke / 2;
            edge.set(inset, inset, w - inset, h - inset);
            paint.setShader(rim); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke);
            paint.setAlpha(Math.round(255 * (.16f + .42f * focus)));
            canvas.drawRoundRect(edge, radius - inset, radius - inset, paint);
            paint.setAlpha(255); paint.setStyle(Paint.Style.FILL); paint.setShader(null);
        }
    }

    /** Animated fill for focusable controls: resting glass to solid white, with an outline for its shadow. */
    static final class Fill extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final int rest;
        private final float radius;
        private float fraction;

        Fill(int restColor, float cornerRadius) { rest = restColor; radius = cornerRadius; }
        void fraction(float value) { fraction = value; invalidateSelf(); }
        private float corner(Rect b) { return radius < 0 ? Math.min(b.width(), b.height()) / 2f : radius; }
        @Override public void draw(Canvas canvas) {
            Rect b = getBounds(); box.set(b); paint.setColor(lerp(rest, FOCUS, fraction));
            float r = corner(b); canvas.drawRoundRect(box, r, r, paint);
        }
        @Override public void getOutline(Outline outline) { Rect b = getBounds(); outline.setRoundRect(b, corner(b)); outline.setAlpha(.55f * fraction); }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Line glyphs in the spirit of tvOS symbols, drawn natively so no icon assets are bundled. */
    static final class Glyph extends Drawable {
        static final int GEAR = 1, PHOTO = 2, CYCLE = 3, GRID = 4, RELOAD = 5, DOWNLOAD = 6, HOUSE = 7,
            HOUSE_CHECK = 8, LIFEBUOY = 9, CHECK = 10, CHEVRON_UP = 11, MOVE = 12, HIDE = 13, INFO = 14,
            FRONT = 15, COMPUTER = 16, BACK = 17;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;
        private float weight = .075f;

        Glyph(int kind, int color) {
            this.kind = kind; paint.setColor(color); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        Glyph weight(float value) { weight = value; return this; }
        void color(int value) { if (paint.getColor() != value) { paint.setColor(value); invalidateSelf(); } }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), x0 = b.exactCenterX() - s / 2, y0 = b.exactCenterY() - s / 2;
            paint.setStrokeWidth(s * weight); path.reset();
            canvas.save(); canvas.translate(x0, y0); canvas.scale(s, s); paint.setStrokeWidth(weight);
            switch (kind) {
                case GEAR:
                    for (int i = 0; i < 32; i++) {
                        double a = (i - .5) * Math.PI / 16; float r = (i + 1) % 4 < 2 ? .42f : .32f;
                        float x = .5f + (float) Math.cos(a) * r, y = .5f + (float) Math.sin(a) * r;
                        if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    path.close(); path.addCircle(.5f, .5f, .12f, Path.Direction.CW); break;
                case PHOTO:
                    path.addRoundRect(.08f, .18f, .92f, .82f, .1f, .1f, Path.Direction.CW);
                    path.moveTo(.17f, .72f); path.lineTo(.4f, .47f); path.lineTo(.56f, .62f); path.lineTo(.68f, .52f); path.lineTo(.84f, .7f);
                    path.addCircle(.68f, .35f, .06f, Path.Direction.CW); break;
                case CYCLE:
                    arc(.5f, .5f, .33f, 200, 135, true); arc(.5f, .5f, .33f, 20, 135, true); break;
                case GRID:
                    path.addRoundRect(.13f, .13f, .44f, .44f, .07f, .07f, Path.Direction.CW);
                    path.addRoundRect(.56f, .13f, .87f, .44f, .07f, .07f, Path.Direction.CW);
                    path.addRoundRect(.13f, .56f, .44f, .87f, .07f, .07f, Path.Direction.CW);
                    path.addRoundRect(.56f, .56f, .87f, .87f, .07f, .07f, Path.Direction.CW); break;
                case RELOAD: arc(.5f, .52f, .34f, -60, 300, true); break;
                case DOWNLOAD:
                    path.addCircle(.5f, .5f, .4f, Path.Direction.CW);
                    path.moveTo(.5f, .28f); path.lineTo(.5f, .69f); path.moveTo(.35f, .55f); path.lineTo(.5f, .7f); path.lineTo(.65f, .55f); break;
                case HOUSE: case HOUSE_CHECK:
                    path.moveTo(.12f, .5f); path.lineTo(.5f, .15f); path.lineTo(.88f, .5f);
                    path.moveTo(.23f, .41f); path.lineTo(.23f, .85f); path.lineTo(.77f, .85f); path.lineTo(.77f, .41f);
                    if (kind == HOUSE) { path.moveTo(.42f, .85f); path.lineTo(.42f, .64f); path.lineTo(.58f, .64f); path.lineTo(.58f, .85f); }
                    else { path.moveTo(.37f, .64f); path.lineTo(.47f, .74f); path.lineTo(.64f, .55f); }
                    break;
                case LIFEBUOY:
                    path.addCircle(.5f, .5f, .4f, Path.Direction.CW); path.addCircle(.5f, .5f, .17f, Path.Direction.CW);
                    for (int i = 0; i < 4; i++) {
                        double a = Math.PI / 4 + i * Math.PI / 2; float c = (float) Math.cos(a), d = (float) Math.sin(a);
                        path.moveTo(.5f + c * .17f, .5f + d * .17f); path.lineTo(.5f + c * .4f, .5f + d * .4f);
                    }
                    break;
                case CHECK: path.moveTo(.18f, .53f); path.lineTo(.41f, .75f); path.lineTo(.84f, .27f); break;
                case CHEVRON_UP: path.moveTo(.18f, .66f); path.lineTo(.5f, .36f); path.lineTo(.82f, .66f); break;
                case MOVE:
                    path.moveTo(.5f, .12f); path.lineTo(.5f, .88f); path.moveTo(.12f, .5f); path.lineTo(.88f, .5f);
                    head(.5f, .12f, 0, -1); head(.5f, .88f, 0, 1); head(.12f, .5f, -1, 0); head(.88f, .5f, 1, 0); break;
                case HIDE:
                    path.moveTo(.08f, .5f); path.quadTo(.5f, .1f, .92f, .5f); path.quadTo(.5f, .9f, .08f, .5f);
                    path.addCircle(.5f, .5f, .12f, Path.Direction.CW); path.moveTo(.18f, .84f); path.lineTo(.82f, .16f); break;
                case INFO:
                    path.addCircle(.5f, .5f, .4f, Path.Direction.CW); path.moveTo(.5f, .46f); path.lineTo(.5f, .7f);
                    path.moveTo(.5f, .31f); path.lineTo(.5f, .315f); break;
                case FRONT:
                    path.moveTo(.18f, .16f); path.lineTo(.18f, .84f); path.moveTo(.84f, .5f); path.lineTo(.32f, .5f);
                    path.moveTo(.5f, .32f); path.lineTo(.32f, .5f); path.lineTo(.5f, .68f); break;
                case COMPUTER:
                    path.addRoundRect(.1f, .16f, .9f, .68f, .06f, .06f, Path.Direction.CW);
                    path.moveTo(.5f, .68f); path.lineTo(.5f, .82f); path.moveTo(.32f, .84f); path.lineTo(.68f, .84f); break;
                case BACK: path.moveTo(.62f, .2f); path.lineTo(.32f, .5f); path.lineTo(.62f, .8f); break;
                default: break;
            }
            canvas.drawPath(path, paint); canvas.restore();
        }
        /** Arc with an arrowhead at its end, in unit coordinates. */
        private void arc(float cx, float cy, float r, float start, float sweep, boolean arrow) {
            path.addArc(cx - r, cy - r, cx + r, cy + r, start, sweep);
            if (!arrow) return;
            double end = Math.toRadians(start + sweep);
            float x = cx + r * (float) Math.cos(end), y = cy + r * (float) Math.sin(end);
            float tx = -(float) Math.sin(end), ty = (float) Math.cos(end);
            head(x, y, tx, ty);
        }
        private void head(float x, float y, float dx, float dy) {
            float len = .13f, c = (float) Math.cos(Math.toRadians(150)), s = (float) Math.sin(Math.toRadians(150));
            path.moveTo(x + len * (dx * c - dy * s), y + len * (dx * s + dy * c)); path.lineTo(x, y);
            path.lineTo(x + len * (dx * c + dy * s), y + len * (-dx * s + dy * c));
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
