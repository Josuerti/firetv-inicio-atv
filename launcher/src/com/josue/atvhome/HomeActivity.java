package com.josue.atvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pantalla de inicio estilo Apple TV: reloj, pastillas, título con resplandor, dock y cuadrícula. */
public class HomeActivity extends Activity {
    static final String EXTRA_FROM_HOME_KEY = "from_home_key";
    static final int COLS = 5;
    static final String[] DEFAULT_DOCK = {
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.firetv.youtube",
            "com.crunchyroll.crunchyroid",
            "com.stremio.one"};
    static final Locale ES = new Locale("es", "ES");

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private float dp;
    private int tileW, tileH, screenW, screenH;

    private FrameLayout root, header;
    private Scroller scroll;
    private LinearLayout content, dock, grid, recentRow;
    private TextView greeting, clock, date, weather, title, wifiPill, gridTitle, recentTitle, dockHint;
    private long weatherAt;
    private GradientDrawable glowBg;
    private int glowColor = 0xFF3A3A3C;
    private ValueAnimator glowAnim;

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> dockApps = new ArrayList<>();
    private final List<AppEntry> gridApps = new ArrayList<>();
    private final List<String> recentPkgs = new ArrayList<>();
    private String pendingFocusPkg;
    private boolean pendingFocusDock = true;
    private boolean receiversOn;
    private boolean released;

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        tileW = px(144);
        tileH = px(81);
        prefs = getSharedPreferences("atv", MODE_PRIVATE);
        buildChrome();
        setContentView(root);

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
        IntentFilter sf = new IntentFilter();
        sf.addAction(Intent.ACTION_TIME_TICK);
        sf.addAction(Intent.ACTION_TIME_CHANGED);
        sf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        sf.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        sf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(sysReceiver, sf);
        receiversOn = true;
        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        updateWifi();
        refreshWeather();
        if (!released && !all.isEmpty()) refreshRecents();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (released) {
            released = false;
            reload();
        }
    }

    /** Al irnos a segundo plano soltamos banners e iconos: la RAM es para la app que estás viendo. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN && !released) {
            released = true;
            dock.removeAllViews();
            grid.removeAllViews();
            recentRow.removeAllViews();
            all.clear();
            dockApps.clear();
            gridApps.clear();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Botón Inicio estando ya aquí: volver arriba, al primer icono del dock.
        goTop();
    }

    @Override
    public void onBackPressed() {
        // Somos la pantalla de inicio: Atrás solo nos devuelve al dock.
        goTop();
    }

    @Override
    protected void onDestroy() {
        if (receiversOn) {
            unregisterReceiver(pkgReceiver);
            unregisterReceiver(sysReceiver);
        }
        io.shutdownNow();
        super.onDestroy();
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            reload();
        }
    };

    private final BroadcastReceiver sysReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
            updateWifi();
            refreshWeather();
        }
    };

    // ---------------------------------------------------------------- estructura fija

    private void buildChrome() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowBg.setGradientRadius(px(640));
        glowBg.setGradientCenter(0.5f, 1.05f);
        applyGlow(glowColor);
        View glow = new View(this);
        glow.setBackground(glowBg);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        scroll = new Scroller(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, 0, 0, px(70));
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        int dockBandH = tileH + px(36);
        header = new FrameLayout(this);
        header.setClipChildren(false);
        content.addView(header, new LinearLayout.LayoutParams(-1, screenH - dockBandH - px(34)));

        // Arriba a la izquierda: saludo, reloj grande y fino, fecha.
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        greeting = text(17, 0xFFB8B8BD, "sans-serif-light");
        clock = text(78, Color.WHITE, "sans-serif-thin");
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.02f);
        date = text(17, 0xFF8E8E93, "sans-serif");
        weather = text(17, 0xFFB8B8BD, "sans-serif-light");
        weather.setVisibility(View.GONE);
        clockBox.addView(greeting);
        clockBox.addView(clock);
        clockBox.addView(date);
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, -2);
        wl.topMargin = px(10);
        clockBox.addView(weather, wl);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cl.setMargins(px(56), px(30), 0, 0);
        header.addView(clockBox, cl);

        // Arriba a la derecha: pastillas de cristal.
        LinearLayout pills = new LinearLayout(this);
        pills.setOrientation(LinearLayout.HORIZONTAL);
        pills.setClipChildren(false);
        wifiPill = pill("Wi-Fi", v -> openWifi());
        pills.addView(wifiPill);
        pills.addView(pill("Inicio Amazon", v -> openAmazonHome()));
        pills.addView(pill("Ajustes", v -> startSafe(new Intent(Settings.ACTION_SETTINGS))));
        FrameLayout.LayoutParams pl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        pl.setMargins(0, px(38), px(52), 0);
        header.addView(pills, pl);

        // Abajo a la izquierda: nombre de la app enfocada en el dock, con resplandor de su color.
        title = text(36, Color.WHITE, "sans-serif-light");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tl.setMargins(sidePad() + px(10), 0, px(56), px(10));
        header.addView(title, tl);

        // Banda del dock.
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(px(8), px(18), px(8), px(18));
        dock.setBackground(round(0x1FFFFFFF, px(28)));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dockBandH);
        dl.setMargins(px(48), 0, px(48), 0);
        content.addView(dock, dl);

        dockHint = text(15, 0xFF8E8E93, "sans-serif");
        dockHint.setText("Pulsa el botón Menú del mando sobre una app de abajo para añadirla al dock");

        // Recientes: las últimas apps abiertas (solo aparece si hay permiso de acceso al uso).
        recentTitle = text(19, 0xFF8E8E93, "sans-serif-medium");
        recentTitle.setText("Recientes");
        recentTitle.setVisibility(View.GONE);
        LinearLayout.LayoutParams rtl = new LinearLayout.LayoutParams(-2, -2);
        rtl.setMargins(sidePad() + px(10), px(40), 0, px(16));
        content.addView(recentTitle, rtl);
        recentRow = gridRow();
        content.addView(recentRow, recentRow.getLayoutParams());

        gridTitle = text(19, 0xFF8E8E93, "sans-serif-medium");
        gridTitle.setText("Apps");
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, -2);
        gl.setMargins(sidePad() + px(10), px(26), 0, px(16));
        content.addView(gridTitle, gl);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---------------------------------------------------------------- apps

    private void reload() {
        io.execute(() -> {
            final List<AppEntry> list = AppEntry.query(this);
            final List<String> rec = Recents.query(this);
            ui.post(() -> {
                all.clear();
                all.addAll(list);
                recentPkgs.clear();
                recentPkgs.addAll(rec);
                rebuild();
            });
        });
    }

    /** Al volver de una app solo cambia la fila de recientes: no hace falta recargar todo. */
    private void refreshRecents() {
        io.execute(() -> {
            final List<String> rec = Recents.query(this);
            ui.post(() -> {
                if (rec.equals(recentPkgs)) return;
                recentPkgs.clear();
                recentPkgs.addAll(rec);
                buildRecents();
            });
        });
    }

    private void buildRecents() {
        recentRow.removeAllViews();
        Set<String> hidden = hidden();
        for (String p : recentPkgs) {
            AppEntry a = findApp(p);
            if (a == null || hidden.contains(p)) continue;
            recentRow.addView(makeTile(a, false));
            if (recentRow.getChildCount() == COLS) break;
        }
        int vis = recentRow.getChildCount() == 0 ? View.GONE : View.VISIBLE;
        recentRow.setVisibility(vis);
        recentTitle.setVisibility(vis);
    }

    private void refreshWeather() {
        long now = System.currentTimeMillis();
        if (now - weatherAt < 30 * 60 * 1000L) return;
        weatherAt = now;
        io.execute(() -> {
            try {
                final Weather w = Weather.fetch();
                ui.post(() -> {
                    weather.setText(w.line());
                    weather.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                weatherAt = 0; // sin red: se reintenta en el siguiente minuto
            }
        });
    }

    private LinearLayout gridRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(sidePad(), 0, 0, 0);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.bottomMargin = px(14);
        row.setLayoutParams(rl);
        return row;
    }

    private void rebuild() {
        if (pendingFocusPkg == null) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof AppEntry) {
                pendingFocusPkg = ((AppEntry) f.getTag()).pkg;
                pendingFocusDock = f.getParent() == dock;
            }
        }
        Map<String, AppEntry> byPkg = new HashMap<>();
        for (AppEntry a : all) byPkg.put(a.pkg, a);
        Set<String> hidden = hidden();

        dockApps.clear();
        for (String p : dockPkgs()) {
            AppEntry a = byPkg.get(p);
            if (a != null && !dockApps.contains(a) && dockApps.size() < COLS) dockApps.add(a);
        }
        gridApps.clear();
        for (AppEntry a : all) if (!dockApps.contains(a) && !hidden.contains(a.pkg)) gridApps.add(a);

        dock.removeAllViews();
        for (AppEntry a : dockApps) dock.addView(makeTile(a, true));
        if (dockApps.isEmpty()) dock.addView(dockHint);

        buildRecents();

        grid.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < gridApps.size(); i++) {
            if (i % COLS == 0) {
                row = gridRow();
                grid.addView(row);
            }
            row.addView(makeTile(gridApps.get(i), false));
        }
        gridTitle.setVisibility(gridApps.isEmpty() ? View.GONE : View.VISIBLE);

        View target = findTile(pendingFocusPkg, pendingFocusDock);
        if (target == null) target = firstTile();
        pendingFocusPkg = null;
        if (target != null) target.requestFocus();
    }

    private View makeTile(final AppEntry a, final boolean inDock) {
        final FrameLayout card = new FrameLayout(this);
        card.setTag(a);
        card.setFocusable(true);
        card.setClipToOutline(true);
        final int radius = px(10);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });

        if (a.banner != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setImageDrawable(a.banner);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            card.setBackgroundColor(a.tile);
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageDrawable(a.icon);
            int s = Math.round(tileH * 0.62f);
            card.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }

        // Brillo de cristal + borde fino: solo visibles con foco.
        GradientDrawable sheenBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
        sheenBg.setCornerRadius(radius);
        sheenBg.setStroke(Math.max(1, px(1)), 0x66FFFFFF);
        final View sheen = new View(this);
        sheen.setBackground(sheenBg);
        sheen.setAlpha(0f);
        card.addView(sheen, new FrameLayout.LayoutParams(-1, -1));

        final TextView label;
        View tile;
        if (inDock) {
            label = null;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tileW, tileH);
            lp.setMargins(px(10), 0, px(10), 0);
            card.setLayoutParams(lp);
            tile = card;
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            col.addView(card, new LinearLayout.LayoutParams(tileW, tileH));
            label = text(13, 0xFFEDEDED, "sans-serif");
            label.setText(a.label);
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setAlpha(0f);
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(tileW, -2);
            ll.topMargin = px(10);
            col.addView(label, ll);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.setMargins(px(10), 0, px(10), 0);
            col.setLayoutParams(cp);
            tile = col;
        }

        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.11f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? px(16) : 0)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            sheen.animate().alpha(has ? 1f : 0f).setDuration(170).start();
            if (label != null) {
                label.animate().alpha(has ? 1f : 0f).translationY(has ? px(6) : 0).setDuration(170).start();
            }
            if (has) onAppFocused(a, inDock, v);
        });
        card.setOnClickListener(v -> startSafe(a.launchIntent()));
        card.setOnLongClickListener(v -> {
            showMenu(a, inDock);
            return true;
        });
        card.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (ev.getAction() == KeyEvent.ACTION_UP) showMenu(a, inDock);
                return true;
            }
            return false;
        });
        return tile;
    }

    private void onAppFocused(AppEntry a, boolean inDock, View card) {
        if (inDock) {
            if (!a.label.contentEquals(title.getText())) {
                title.setText(a.label);
                title.setAlpha(0.3f);
            }
            title.animate().alpha(1f).setDuration(220).start();
            title.setShadowLayer(px(22), 0, 0, (a.glow & 0x00FFFFFF) | 0xAA000000);
            scroll.smoothScrollTo(0, 0);
        } else {
            title.animate().alpha(0f).setDuration(150).start();
            View row = (View) card.getParent().getParent();
            int rowBottom = row.getBottom() + (row.getParent() == grid ? grid.getTop() : 0);
            int minY = dock.getTop() - px(26);
            scroll.smoothScrollTo(0, Math.max(minY, rowBottom + px(56) - scroll.getHeight()));
        }
        animateGlow(a.glow);
    }

    private void showMenu(final AppEntry a, boolean inDock) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        final List<String> d = new ArrayList<>();
        for (AppEntry e : dockApps) d.add(e.pkg);
        final int i = d.indexOf(a.pkg);
        final Set<String> hidden = hidden();

        if (inDock) {
            if (i > 0) {
                labels.add("Mover a la izquierda");
                acts.add(() -> { Collections.swap(d, i, i - 1); saveDock(d); refocus(a.pkg, true); });
            }
            if (i >= 0 && i < d.size() - 1) {
                labels.add("Mover a la derecha");
                acts.add(() -> { Collections.swap(d, i, i + 1); saveDock(d); refocus(a.pkg, true); });
            }
            labels.add("Quitar del dock");
            acts.add(() -> { d.remove(a.pkg); saveDock(d); refocus(a.pkg, false); });
        } else {
            labels.add("Añadir al dock");
            acts.add(() -> {
                if (d.size() >= COLS) {
                    toast("El dock está lleno (máximo " + COLS + "). Quita una app primero.");
                } else {
                    d.add(a.pkg);
                    saveDock(d);
                    refocus(a.pkg, true);
                }
            });
            labels.add("Ocultar app");
            acts.add(() -> {
                hidden.add(a.pkg);
                prefs.edit().putStringSet("hidden", hidden).apply();
                rebuild();
            });
        }
        labels.add("Información de la app");
        acts.add(() -> startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", a.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        if (!hidden.isEmpty()) {
            labels.add("Mostrar apps ocultas (" + hidden.size() + ")");
            acts.add(() -> { prefs.edit().remove("hidden").apply(); refocus(a.pkg, inDock); });
        }

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(labels.toArray(new String[0]), (dlg, w) -> acts.get(w).run())
                .show();
    }

    private void refocus(String pkg, boolean inDock) {
        pendingFocusPkg = pkg;
        pendingFocusDock = inDock;
        rebuild();
    }

    private List<String> dockPkgs() {
        String s = prefs.getString("dock", null);
        if (s == null) return Arrays.asList(DEFAULT_DOCK);
        List<String> l = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) l.add(p);
        return l;
    }

    private void saveDock(List<String> pkgs) {
        prefs.edit().putString("dock", TextUtils.join(",", pkgs)).apply();
    }

    private Set<String> hidden() {
        return new HashSet<>(prefs.getStringSet("hidden", Collections.<String>emptySet()));
    }

    private View findTile(String pkg, boolean inDock) {
        if (pkg == null) return null;
        View v = inDock ? dock.findViewWithTag(findApp(pkg)) : grid.findViewWithTag(findApp(pkg));
        if (v == null) v = content.findViewWithTag(findApp(pkg));
        return v;
    }

    private AppEntry findApp(String pkg) {
        for (AppEntry a : all) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private View firstTile() {
        if (!dockApps.isEmpty()) return dock.getChildAt(0);
        if (!gridApps.isEmpty()) return grid.findViewWithTag(gridApps.get(0));
        return null;
    }

    private void goTop() {
        scroll.smoothScrollTo(0, 0);
        View f = getCurrentFocus();
        if (f == null || f.getParent() != dock) {
            View first = firstTile();
            if (first != null) first.requestFocus();
        }
    }

    // ---------------------------------------------------------------- reloj, red, acciones

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", ES).format(now));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", ES).format(now);
        date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Buenas noches" : h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches");
    }

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        String label;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) {
                label = "Sin conexión";
            } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                String ssid = null;
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                if (wi != null) ssid = wi.getSSID();
                if (ssid != null) ssid = ssid.replace("\"", "").trim();
                label = (ssid == null || ssid.isEmpty() || ssid.contains("unknown ssid")) ? "Wi-Fi" : "Wi-Fi · " + ssid;
            } else {
                label = "Ethernet";
            }
        } catch (Exception e) {
            label = "Wi-Fi";
        }
        wifiPill.setText(label);
    }

    private void openWifi() {
        if (!startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS))) startSafe(new Intent(Settings.ACTION_SETTINGS));
    }

    private void openAmazonHome() {
        HomeService.allowAmazon = true;
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        i.setPackage(HomeService.AMAZON_HOME);
        if (!startSafe(i)) HomeService.allowAmazon = false;
    }

    private boolean startSafe(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            toast("No se pudo abrir");
            return false;
        }
    }

    // ---------------------------------------------------------------- resplandor y utilidades

    private void animateGlow(int target) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, target);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(an -> {
            glowColor = (Integer) an.getAnimatedValue();
            applyGlow(glowColor);
        });
        glowAnim.start();
    }

    private void applyGlow(int c) {
        int rgb = c & 0x00FFFFFF;
        glowBg.setColors(new int[]{rgb | 0x5C000000, rgb | 0x1A000000, 0x00000000});
    }

    private TextView pill(String label, View.OnClickListener click) {
        TextView t = text(15, 0xFFEDEDED, "sans-serif-medium");
        t.setText(label);
        t.setPadding(px(18), px(8), px(18), px(8));
        t.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, round(0xF2FFFFFF, px(20)));
        bg.addState(new int[]{}, round(0x2EFFFFFF, px(20)));
        t.setBackground(bg);
        t.setTextColor(new ColorStateList(
                new int[][]{{android.R.attr.state_focused}, {}},
                new int[]{0xFF000000, 0xFFEDEDED}));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(10);
        t.setLayoutParams(lp);
        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
            if (has) {
                title.animate().alpha(0f).setDuration(150).start();
                scroll.smoothScrollTo(0, 0);
            }
        });
        t.setOnClickListener(click);
        return t;
    }

    private TextView text(float sp, int color, String family) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        return t;
    }

    private static GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(r);
        return g;
    }

    /** Margen izquierdo para que la cuadrícula quede alineada con los huecos del dock. */
    private int sidePad() {
        return Math.max(0, (screenW - COLS * (tileW + px(20))) / 2);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** ScrollView que no se mueve sola: el desplazamiento lo decide onAppFocused. */
    private final class Scroller extends ScrollView {
        Scroller(Context c) {
            super(c);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) {
            return 0;
        }

        @Override
        public boolean executeKeyEvent(KeyEvent event) {
            return false;
        }

        @Override
        protected void onScrollChanged(int l, int t, int ol, int ot) {
            super.onScrollChanged(l, t, ol, ot);
            if (header != null) header.setAlpha(1f - Math.min(1f, t / (float) px(220)));
        }
    }
}
