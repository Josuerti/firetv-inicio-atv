package com.josue.atvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Una app lanzable, con su banner/icono y los colores sacados de su arte. */
final class AppEntry {
    /** Pantallas de Amazon que ya tienen acceso propio (pastillas) o que no tiene sentido lanzar. */
    static final Set<String> EXCLUDE = new HashSet<>(Arrays.asList(
            "com.amazon.tv.launcher",
            "com.amazon.firehomestarter",
            "com.amazon.tv.settings.v2",
            // Herramientas internas de Fire OS que salen como "apps" con icono genérico.
            "com.amazon.firetv.troubleshooting",
            "com.amazon.ftv.screensaver",
            "com.amazon.ftv.profilepicker",
            "com.amazon.hedwig",
            "com.amazon.ssm",
            "com.amazon.whasettings"));

    final String pkg;
    final String cls;
    final String label;
    final Drawable banner;
    final Drawable icon;
    final boolean leanback;
    /** Fondo oscuro para tiles sin banner. */
    final int tile;
    /** Color vivo para el resplandor de fondo y el título. */
    final int glow;

    private AppEntry(String pkg, String cls, String label, Drawable banner, Drawable icon, boolean leanback) {
        this.pkg = pkg;
        this.cls = cls;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.leanback = leanback;
        int[] c = colors(banner != null ? banner : icon);
        this.tile = c[0];
        this.glow = c[1];
    }

    Intent launchIntent() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER);
        i.setClassName(pkg, cls);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return i;
    }

    static List<AppEntry> query(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Map<String, AppEntry> out = new LinkedHashMap<>();
        collect(ctx, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, out);
        collect(ctx, pm, Intent.CATEGORY_LAUNCHER, false, out);
        List<AppEntry> list = new ArrayList<>(out.values());
        final Collator col = Collator.getInstance(new Locale("es"));
        col.setStrength(Collator.PRIMARY);
        Collections.sort(list, (a, b) -> col.compare(a.label, b.label));
        return list;
    }

    private static void collect(Context ctx, PackageManager pm, String category, boolean leanback,
                                Map<String, AppEntry> out) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            ActivityInfo ai = ri.activityInfo;
            String pkg = ai.packageName;
            if (out.containsKey(pkg) || EXCLUDE.contains(pkg) || pkg.equals(ctx.getPackageName())) continue;
            Drawable banner = null;
            try {
                banner = ai.loadBanner(pm);
            } catch (Exception ignored) {
            }
            Drawable icon = ri.loadIcon(pm);
            // Algunos banners vienen a 1280x720: los reducimos al tamaño del tile para ahorrar RAM.
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int w = Math.round(144 * dm.density), h = Math.round(81 * dm.density);
            if (banner != null) banner = shrink(ctx, banner, w, h);
            if (icon != null) icon = shrink(ctx, icon, h, h);
            String label = String.valueOf(ri.loadLabel(pm)).trim();
            out.put(pkg, new AppEntry(pkg, ai.name, label, banner, icon, leanback));
        }
    }

    private static Drawable shrink(Context ctx, Drawable d, int w, int h) {
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return new BitmapDrawable(ctx.getResources(), bm);
    }

    /** Media ponderada por saturación: los colores de marca pesan más que el gris/negro. */
    private static int[] colors(Drawable d) {
        int fallbackTile = 0xFF2C2C2E, fallbackGlow = 0xFF6E6E73;
        if (d == null) return new int[]{fallbackTile, fallbackGlow};
        int w = 24, h = 14;
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, w, h);
        d.draw(c);
        d.setBounds(old);
        double r = 0, g = 0, b = 0, sum = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 120) continue;
                Color.colorToHSV(p, hsv);
                double wt = 0.15 + hsv[1] * hsv[2] * 3;
                r += Color.red(p) * wt;
                g += Color.green(p) * wt;
                b += Color.blue(p) * wt;
                sum += wt;
            }
        }
        bm.recycle();
        if (sum == 0) return new int[]{fallbackTile, fallbackGlow};
        int avg = Color.rgb((int) (r / sum), (int) (g / sum), (int) (b / sum));
        Color.colorToHSV(avg, hsv);
        float[] glow = hsv.clone();
        glow[1] = Math.min(1f, glow[1] * 1.25f);
        glow[2] = Math.max(glow[2], 0.9f);
        float[] tile = hsv.clone();
        tile[1] = tile[1] * 0.85f;
        tile[2] = 0.18f + 0.25f * tile[2];
        return new int[]{Color.HSVToColor(tile), Color.HSVToColor(glow)};
    }
}
