package io.github.elliclee.livingroom;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.DisplayMetrics;

/**
 * Owns how an app's real banner or icon becomes a 5:3 home card. Worker thread only.
 * Wide banners are used as published; icons are analysed so the glyph, not the Android plate, sets the size.
 */
final class AppArtwork {
    private static final int SOURCE = 320, LAYER = 384;
    private static final float GLYPH_H = .58f, GLYPH_W = .6f;

    /** A drawable rasterised for analysis, with the rectangle it occupies in that raster. */
    private static final class Raster {
        final Drawable drawable; final Bitmap bitmap; final RectF fit; final int[] pixels; final int size;
        Raster(Drawable drawable, int size) {
            this.drawable = drawable; this.size = size;
            bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            int w = drawable.getIntrinsicWidth(), h = drawable.getIntrinsicHeight();
            float scale = w > 0 && h > 0 ? Math.min(size / (float) w, size / (float) h) : 1;
            float dw = w > 0 && h > 0 ? w * scale : size, dh = w > 0 && h > 0 ? h * scale : size;
            fit = new RectF((size - dw) / 2, (size - dh) / 2, (size + dw) / 2, (size + dh) / 2);
            draw(new Canvas(bitmap), drawable, fit);
            pixels = new int[size * size];
            bitmap.getPixels(pixels, 0, size, 0, 0, size, size);
        }
        int at(float x, float y) { return pixels[Math.max(0, Math.min(size - 1, Math.round(y))) * size + Math.max(0, Math.min(size - 1, Math.round(x)))]; }
    }

    private AppArtwork() { }

    static Bitmap render(Context context, ComponentName component, int width) {
        int height = Math.round(width * .6f);
        PackageManager pm = context.getPackageManager();
        Drawable banner = null, icon = null;
        try {
            ActivityInfo activity = pm.getActivityInfo(component, 0);
            Resources resources = pm.getResourcesForApplication(activity.applicationInfo);
            banner = highest(resources, activity.banner != 0 ? activity.banner : activity.applicationInfo.banner);
            if (banner == null) banner = activity.loadBanner(pm);
            icon = highest(resources, activity.getIconResource());
            if (icon == null) icon = activity.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            // The package changed or hides its resources; fall back to the platform icon below.
        }
        Bitmap card = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(card);
        if (icon == null) icon = pm.getDefaultActivityIcon();
        try {
            // Some TV system apps declare their square icon as the banner; only a wide image is a banner.
            if (banner != null && banner.getIntrinsicHeight() > 0 && banner.getIntrinsicWidth() >= banner.getIntrinsicHeight() * 1.5f) {
                float scale = Math.max(width / (float) banner.getIntrinsicWidth(), height / (float) banner.getIntrinsicHeight());
                float dw = banner.getIntrinsicWidth() * scale, dh = banner.getIntrinsicHeight() * scale;
                draw(canvas, banner, new RectF((width - dw) / 2, (height - dh) / 2, (width + dw) / 2, (height + dh) / 2));
            } else if (!(Build.VERSION.SDK_INT >= 26 && icon instanceof AdaptiveIconDrawable && adaptive(canvas, (AdaptiveIconDrawable) icon, width, height))) {
                legacy(canvas, new Raster(icon, SOURCE), width, height);
            }
        } catch (RuntimeException malformed) {
            // A third-party drawable that cannot be rasterised keeps a neutral card instead of an empty slot.
            canvas.drawColor(0, PorterDuff.Mode.CLEAR); tile(canvas, 0xff3a3a3c, width, height);
        }
        return card;
    }

    private static Drawable highest(Resources resources, int id) {
        if (id == 0) return null;
        try { return resources.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null); }
        catch (RuntimeException unavailable) { return null; }
    }

    private static void draw(Canvas canvas, Drawable drawable, RectF box) {
        drawable.setBounds(Math.round(box.left), Math.round(box.top), Math.round(box.right), Math.round(box.bottom));
        drawable.draw(canvas);
    }

    /** Adaptive icons separate plate and glyph: use the background as the card and size the foreground glyph. */
    private static boolean adaptive(Canvas canvas, AdaptiveIconDrawable icon, int w, int h) {
        Drawable back = icon.getBackground(), front = icon.getForeground();
        if (front == null) return false;
        Raster glyph = new Raster(front, LAYER);
        Rect bounds = bounds(glyph, 24);
        if (bounds == null) return false;
        Raster plate = back == null ? null : new Raster(back, LAYER);
        int[] stats = plate == null ? null : stats(plate, new Rect(LAYER / 6, LAYER / 6, LAYER * 5 / 6, LAYER * 5 / 6));
        if (stats == null || stats[1] >= 18 || bounds.width() > LAYER * .86f && bounds.height() > LAYER * .86f) {
            // Shaped or full-bleed layers are one illustrated tile: analyse the visible viewport as a square icon.
            Bitmap tile = Bitmap.createBitmap(LAYER, LAYER, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(tile);
            RectF all = new RectF(-LAYER / 4f, -LAYER / 4f, LAYER * 1.25f, LAYER * 1.25f);
            if (back != null) draw(c, back, all);
            draw(c, front, all);
            legacy(canvas, new Raster(new android.graphics.drawable.BitmapDrawable(null, tile), SOURCE), w, h);
            return true;
        }
        tile(canvas, stats[0], w, h);
        glyph(canvas, glyph, bounds, w, h, false);
        return true;
    }

    private static void legacy(Canvas canvas, Raster icon, int w, int h) {
        Rect b = bounds(icon, 16);
        if (b == null) { tile(canvas, 0xff3a3a3c, w, h); return; }
        float inset = Math.max(2, Math.min(b.width(), b.height()) * .06f);
        boolean edges = true;
        for (float t : new float[]{.3f, .5f, .7f}) {
            edges &= opaque(icon.at(b.left + b.width() * t, b.top + inset)) && opaque(icon.at(b.left + b.width() * t, b.bottom - 1 - inset))
                && opaque(icon.at(b.left + inset, b.top + b.height() * t)) && opaque(icon.at(b.right - 1 - inset, b.top + b.height() * t));
        }
        if (!edges) { transparentGlyph(canvas, icon, b, w, h); return; }
        float cx = b.exactCenterX(), cy = b.exactCenterY(), dx = b.width() * .1f, dy = b.height() * .1f;
        boolean square = opaque(icon.at(b.left + dx, b.top + dy)) && opaque(icon.at(b.right - dx, b.top + dy))
            && opaque(icon.at(b.left + dx, b.bottom - dy)) && opaque(icon.at(b.right - dx, b.bottom - dy));
        int[] ring = new int[64];
        for (int i = 0; i < 64; i++) {
            if (square) {
                float t = .15f + .7f * (i % 16) / 15f;
                int side = i / 16;
                ring[i] = side == 0 ? icon.at(b.left + b.width() * t, b.top + inset) : side == 1 ? icon.at(b.right - 1 - inset, b.top + b.height() * t)
                    : side == 2 ? icon.at(b.right - b.width() * t, b.bottom - 1 - inset) : icon.at(b.left + inset, b.bottom - b.height() * t);
            } else {
                double a = i * Math.PI * 2 / 64; float r = Math.min(b.width(), b.height()) / 2f - inset * 1.5f;
                ring[i] = icon.at(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
            }
        }
        int mean = mean(ring);
        int spread = 0, step = 0;
        int[] distances = new int[ring.length];
        for (int i = 0; i < ring.length; i++) { distances[i] = distance(ring[i], mean); step += distance(ring[i], ring[(i + 1) % ring.length]); }
        java.util.Arrays.sort(distances); spread = distances[distances.length * 9 / 10]; step /= ring.length;
        if (spread < 20) merge(canvas, icon, b, mean, square, w, h);
        else if (!square) transparentGlyph(canvas, icon, b, w, h);
        else if (step < 7) extend(canvas, icon, b, w, h);
        else ambient(canvas, icon, b, w, h);
    }

    /** A solid plate becomes the whole card; the glyph inside it is scaled to the shared glyph size. */
    private static void merge(Canvas canvas, Raster icon, Rect plate, int color, boolean square, int w, int h) {
        canvas.drawColor(color | 0xff000000);
        Rect inner = new Rect(plate); inner.inset(Math.round(plate.width() * .07f), Math.round(plate.height() * .07f));
        Rect content = null;
        for (int y = inner.top; y < inner.bottom; y++) for (int x = inner.left; x < inner.right; x++) {
            int c = icon.pixels[y * icon.size + x];
            if ((c >>> 24) > 128 && distance(c, color) > 44) {
                if (!square && Math.hypot(x - plate.exactCenterX(), y - plate.exactCenterY()) > plate.width() * .43f) continue;
                if (content == null) content = new Rect(x, y, x + 1, y + 1); else content.union(x, y, x + 1, y + 1);
            }
        }
        if (content == null) { gloss(canvas, w, h); return; }
        // A tiny mark keeps a sensible size instead of being blown up to the full glyph box.
        float scale = Math.min(GLYPH_H * h / Math.max(content.height(), plate.height() * .22f), GLYPH_W * w / Math.max(content.width(), plate.width() * .22f));
        Matrix map = centered(content, scale, w, h);
        Bitmap layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(layer);
        RectF box = new RectF(icon.fit); map.mapRect(box); draw(c, icon.drawable, box);
        // Feather the old plate border so slight shading differences cannot outline it on the card.
        RectF edge = new RectF(plate); map.mapRect(edge);
        float feather = Math.max(2, edge.width() * .05f);
        edge.inset(feather * 1.4f, feather * 1.4f);
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        mask.setMaskFilter(new BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL));
        Bitmap alpha = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas a = new Canvas(alpha);
        if (square) a.drawRoundRect(edge, edge.width() * .18f, edge.height() * .18f, mask); else a.drawOval(edge, mask);
        Paint in = new Paint(); in.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        c.drawBitmap(alpha, 0, 0, in);
        canvas.drawBitmap(layer, 0, 0, new Paint(Paint.FILTER_BITMAP_FLAG));
        gloss(canvas, w, h);
        layer.recycle(); alpha.recycle();
    }

    /** A smooth gradient plate continues past its square sides, so the artwork reads as one surface. */
    private static void extend(Canvas canvas, Raster icon, Rect plate, int w, int h) {
        int gap = 0;
        while (gap < plate.width() / 3 && !opaque(icon.at(plate.left + gap, plate.top + gap))) gap++;
        int inset = gap + Math.max(2, plate.width() / 50);
        Rect crop = new Rect(plate); crop.inset(inset, inset);
        if (crop.width() <= 4 || crop.height() <= 4) { ambient(canvas, icon, plate, w, h); return; }
        Bitmap part = Bitmap.createBitmap(icon.bitmap, crop.left, crop.top, crop.width(), crop.height());
        BitmapShader shader = new BitmapShader(part, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float scale = h / (float) crop.height();
        Matrix m = new Matrix(); m.setScale(scale, scale); m.postTranslate((w - crop.width() * scale) / 2, 0);
        shader.setLocalMatrix(m);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); p.setShader(shader);
        canvas.drawRect(0, 0, w, h, p);
        gloss(canvas, w, h);
    }

    /** Pictorial icons keep their artwork: a blurred field of the same image carries a crisp centred tile. */
    private static void ambient(Canvas canvas, Raster icon, Rect plate, int w, int h) {
        Bitmap part = Bitmap.createBitmap(icon.bitmap, plate.left, plate.top, plate.width(), plate.height());
        int fw = 60, fh = 36;
        Bitmap field = Bitmap.createScaledBitmap(part, fw, fw, true);
        Bitmap cropped = Bitmap.createBitmap(field, 0, (fw - fh) / 2, fw, fh);
        int[] px = new int[fw * fh]; cropped.getPixels(px, 0, fw, 0, 0, fw, fh);
        for (int i = 0; i < px.length; i++) px[i] |= 0xff000000;
        TvAppearance.blur(px, fw, fh, 4);
        cropped = Bitmap.createBitmap(px, fw, fh, Bitmap.Config.ARGB_8888);
        canvas.drawBitmap(cropped, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.drawColor(0x38000000);
        float size = h * .74f, scale = size / Math.max(plate.width(), plate.height());
        RectF box = new RectF(icon.fit);
        Matrix map = centered(plate, scale, w, h); map.mapRect(box);
        RectF tile = new RectF(plate); map.mapRect(tile);
        shadow(canvas, tile, size * .2f, h);
        Bitmap layer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(layer); draw(c, icon.drawable, box);
        Paint clip = new Paint(Paint.ANTI_ALIAS_FLAG); clip.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        Bitmap alpha = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        float corner = size * .2f;
        new Canvas(alpha).drawRoundRect(tile, corner, corner, new Paint(Paint.ANTI_ALIAS_FLAG));
        c.drawBitmap(alpha, 0, 0, clip);
        canvas.drawBitmap(layer, 0, 0, null);
        gloss(canvas, w, h);
        layer.recycle(); alpha.recycle(); field.recycle(); part.recycle();
    }

    /** A glyph without a plate gets a tvOS-like light or graphite card chosen from its own colour. */
    private static void transparentGlyph(Canvas canvas, Raster icon, Rect b, int w, int h) {
        long n = 0, hr = 0, hg = 0, hb = 0, hn = 0; float light = 0, saturation = 0; float[] hsv = new float[3];
        for (int y = b.top; y < b.bottom; y += 2) for (int x = b.left; x < b.right; x += 2) {
            int c = icon.pixels[y * icon.size + x];
            if ((c >>> 24) < 128) continue;
            Color.colorToHSV(c, hsv); n++;
            light += hsv[2]; saturation += hsv[1];
            if (hsv[1] > .35f && hsv[2] > .25f) { hr += Color.red(c); hg += Color.green(c); hb += Color.blue(c); hn++; }
        }
        if (n == 0) { tile(canvas, 0xff3a3a3c, w, h); return; }
        light /= n; saturation /= n;
        int top, bottom;
        if (light > .8f && saturation < .2f) { top = 0xff4a4a4e; bottom = 0xff1f1f22; }
        else {
            int hue = hn > 0 ? Color.rgb((int) (hr / hn), (int) (hg / hn), (int) (hb / hn)) : 0xff8e8e93;
            top = TvAppearance.lerp(0xffffffff, hue, saturation > .3f ? .07f : .02f);
            bottom = TvAppearance.lerp(0xffe3e3e8, hue, saturation > .3f ? .14f : .04f);
        }
        Paint fill = new Paint(); fill.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, fill);
        glyph(canvas, icon, b, w, h, true);
    }

    private static void glyph(Canvas canvas, Raster source, Rect content, int w, int h, boolean lift) {
        float scale = Math.min(GLYPH_H * h / content.height(), GLYPH_W * w / content.width());
        Matrix map = centered(content, scale, w, h);
        RectF box = new RectF(source.fit); map.mapRect(box);
        if (lift) {
            Paint blur = new Paint(); blur.setMaskFilter(new BlurMaskFilter(Math.max(1, h * .03f / scale), BlurMaskFilter.Blur.NORMAL));
            int[] offset = new int[2];
            Bitmap shade = source.bitmap.extractAlpha(blur, offset);
            Matrix m = new Matrix(map); m.preTranslate(offset[0], offset[1]); m.postTranslate(0, h * .02f);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG); p.setColor(0x33000000);
            canvas.drawBitmap(shade, m, p); shade.recycle();
        }
        draw(canvas, source.drawable, box);
    }

    private static void shadow(Canvas canvas, RectF tile, float radius, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(0x59000000);
        p.setMaskFilter(new BlurMaskFilter(Math.max(1, h * .05f), BlurMaskFilter.Blur.NORMAL));
        RectF s = new RectF(tile); s.offset(0, h * .03f);
        canvas.drawRoundRect(s, radius, radius, p);
    }

    /** Maps content so its centre lands on the card centre at the given scale. */
    private static Matrix centered(Rect content, float scale, int w, int h) {
        Matrix m = new Matrix(); m.setTranslate(-content.exactCenterX(), -content.exactCenterY());
        m.postScale(scale, scale); m.postTranslate(w / 2f, h / 2f); return m;
    }

    private static void tile(Canvas canvas, int color, int w, int h) { canvas.drawColor(color | 0xff000000); gloss(canvas, w, h); }

    /** The gentle top light and bottom falloff shared by every generated card face. */
    private static void gloss(Canvas canvas, int w, int h) {
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x1affffff, 0x00ffffff, 0x00000000, 0x1a000000}, new float[]{0, .45f, .55f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
    }

    private static boolean opaque(int color) { return (color >>> 24) > 230; }

    private static Rect bounds(Raster raster, int threshold) {
        int s = raster.size, left = s, top = s, right = -1, bottom = -1;
        for (int y = 0; y < s; y++) for (int x = 0; x < s; x++) if ((raster.pixels[y * s + x] >>> 24) > threshold) {
            if (x < left) left = x; if (x > right) right = x; if (y < top) top = y; if (y > bottom) bottom = y;
        }
        return right < 0 ? null : new Rect(left, top, right + 1, bottom + 1);
    }

    /** Mean opaque colour and 90th-percentile distance from it inside a region. */
    private static int[] stats(Raster raster, Rect region) {
        int count = 0; int[] samples = new int[256];
        for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++)
            samples[count++] = raster.at(region.left + region.width() * (i + .5f) / 16, region.top + region.height() * (j + .5f) / 16);
        int mean = mean(samples); int[] d = new int[count];
        for (int i = 0; i < count; i++) d[i] = distance(samples[i], mean);
        java.util.Arrays.sort(d);
        return new int[]{mean, d[count * 9 / 10]};
    }

    private static int mean(int[] colors) {
        long r = 0, g = 0, b = 0;
        for (int c : colors) { r += Color.red(c); g += Color.green(c); b += Color.blue(c); }
        return Color.rgb((int) (r / colors.length), (int) (g / colors.length), (int) (b / colors.length));
    }

    private static int distance(int a, int b) {
        int dr = Color.red(a) - Color.red(b), dg = Color.green(a) - Color.green(b), db = Color.blue(a) - Color.blue(b);
        return (int) Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
