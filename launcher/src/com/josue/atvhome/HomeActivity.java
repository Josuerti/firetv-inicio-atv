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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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

/**
 * Pantalla de inicio estilo tvOS: pestañas Inicio / Ajustes, saludo, tarjeta de partidos
 * (Emelec, Real Madrid y Champions), clima por horas, dock y, bajando, todas las apps.
 * Tema claro u oscuro. Los datos solo se actualizan mientras la pantalla está a la vista.
 */
public class HomeActivity extends Activity {
    static final String EXTRA_FROM_HOME_KEY = "from_home_key";
    static final int DOCK_MAX = 6, GRID_COLS = 6, MATCH_MAX = 8;
    static final String[] DEFAULT_DOCK = {
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.firetv.youtube",
            "com.crunchyroll.crunchyroid",
            "com.stremio.one",
            "com.cbs.ca"};
    static final Locale ES = new Locale("es", "ES");
    static final int PAGE_HOME = 0, PAGE_SETTINGS = 1;
    static final int KIND_DOCK = 0, KIND_GRID = 1;
    static final int TAB_H = 34, TAB_INSET = 4;
    static final long WEATHER_EVERY = 30 * 60 * 1000L, MATCHES_EVERY = 10 * 60 * 1000L, LIVE_EVERY = 60 * 1000L;

    /** Colores de cada tema. */
    private static final class Palette {
        final boolean light;
        final int bgTop, bgBottom, card, text, sub, tabBar, tabSel, tabText, glowAlpha;

        Palette(boolean light) {
            this.light = light;
            bgTop = light ? 0xFFF4F5F8 : 0xFF1C1C1E;
            bgBottom = light ? 0xFFD9DCE3 : 0xFF000000;
            card = light ? 0xB3FFFFFF : 0x1FFFFFFF;
            text = light ? 0xFF1C1C1E : 0xFFFFFFFF;
            sub = light ? 0xFF6E6E73 : 0xFF98989D;
            tabBar = light ? 0x8CFFFFFF : 0x1FFFFFFF;
            tabSel = light ? 0xBFFFFFFF : 0x40FFFFFF;
            tabText = light ? 0xFF3A3A3C : 0xFFEBEBF0;
            glowAlpha = light ? 0x38 : 0x5C;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Palette pal;
    private float dp;
    private int screenW, screenH;

    private GradientDrawable rootBg, glowBg;
    private int glowColor = 0xFF8E8E93;
    private ValueAnimator glowAnim;

    private LinearLayout tabBar;
    private final TextView[] tabs = new TextView[2];
    private final View[] pages = new View[2];
    private int page = PAGE_HOME;

    private ScrollView homeScroll;
    private TextView clock, greeting, date, wifiRow, hiddenRow, matchEmpty;
    private LinearLayout dock, matchRow, appsGrid, settingsList;
    private TextView wCity, wIcon, wTemp, wDesc;
    private final TextView[][] wHours = new TextView[Weather.HOURS][3];

    private boolean visible, liveShown;
    private long weatherAt, matchesAt;

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> dockApps = new ArrayList<>();
    private String pendingFocusPkg;
    private boolean pendingFocusDock = true;
    private boolean released;

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        prefs = getSharedPreferences("atv", MODE_PRIVATE);
        pal = new Palette(!"dark".equals(prefs.getString("theme", "light")));
        setContentView(buildChrome());

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
        showPage(PAGE_HOME);
        reload();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (released) {
            released = false;
            reload();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        updateClock();
        updateWifi();
        refreshData();
        ensureHomeService();
    }

    @Override
    protected void onPause() {
        visible = false; // mientras ves otra app no se descarga nada
        super.onPause();
    }

    /** Al irnos a segundo plano soltamos banners e iconos: la RAM es para la app que estás viendo. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN && !released) {
            released = true;
            dock.removeAllViews();
            appsGrid.removeAllViews();
            all.clear();
            dockApps.clear();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        goHome(); // botón Inicio estando ya aquí
    }

    @Override
    public void onBackPressed() {
        goHome(); // somos la pantalla de inicio: Atrás solo vuelve al dock
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(pkgReceiver);
        unregisterReceiver(sysReceiver);
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
            refreshData(); // cada minuto: comprueba si toca actualizar clima o partidos
        }
    };

    // ---------------------------------------------------------------- estructura

    private View buildChrome() {
        FrameLayout root = new FrameLayout(this);
        rootBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{pal.bgTop, pal.bgBottom});
        root.setBackground(rootBg);
        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowBg.setGradientRadius(px(620));
        glowBg.setGradientCenter(0.5f, 1.05f);
        View glow = new View(this);
        glow.setBackground(glowBg);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        applyGlow(glowColor);

        pages[PAGE_HOME] = buildHomePage();
        pages[PAGE_SETTINGS] = buildSettingsPage();
        for (View p : pages) root.addView(p, new FrameLayout.LayoutParams(-1, -1));

        // Barra superior fija: pestañas centradas (esquinas concéntricas) y reloj a la derecha.
        tabBar = new LinearLayout(this);
        tabBar.setPadding(px(TAB_INSET), px(TAB_INSET), px(TAB_INSET), px(TAB_INSET));
        tabBar.setBackground(round(pal.tabBar, px(TAB_H / 2f + TAB_INSET)));
        String[] names = {"Inicio", "Ajustes"};
        for (int i = 0; i < 2; i++) {
            final int p = i;
            TextView t = text(15, pal.tabText, "sans-serif-medium");
            t.setText(names[i]);
            t.setGravity(Gravity.CENTER);
            t.setIncludeFontPadding(false);
            t.setPadding(px(22), 0, px(22), 0);
            t.setLayoutParams(new LinearLayout.LayoutParams(-2, px(TAB_H)));
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    showPage(p);
                    homeScroll.smoothScrollTo(0, 0);
                }
                styleTabs();
            });
            t.setOnKeyListener((v, code, ev) -> {
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && ev.getAction() == KeyEvent.ACTION_DOWN) {
                    View first = pageFirst(page);
                    if (first != null) first.requestFocus();
                    return true;
                }
                return false;
            });
            t.setOnClickListener(v -> {
                View first = pageFirst(p);
                if (first != null) first.requestFocus();
            });
            tabs[i] = t;
            tabBar.addView(t);
        }
        FrameLayout.LayoutParams tbl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tbl.topMargin = px(20);
        root.addView(tabBar, tbl);

        clock = text(22, pal.text, "sans-serif-medium");
        FrameLayout.LayoutParams ckl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        ckl.setMargins(0, px(26), px(52), 0);
        root.addView(clock, ckl);
        return root;
    }

    private View buildHomePage() {
        homeScroll = new ScrollView(this) {
            @Override
            protected void onScrollChanged(int l, int t, int ol, int ot) {
                super.onScrollChanged(l, t, ol, ot);
                // Al bajar a las apps, la barra superior se desvanece.
                float a = 1f - Math.min(1f, t / (float) px(120));
                tabBar.setAlpha(a);
                clock.setAlpha(a);
            }
        };
        homeScroll.setVerticalScrollBarEnabled(false);
        homeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        homeScroll.setClipChildren(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        content.setPadding(0, 0, 0, px(60));
        homeScroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        FrameLayout top = new FrameLayout(this);
        top.setClipChildren(false);
        content.addView(top, new LinearLayout.LayoutParams(-1, screenH));

        LinearLayout hello = new LinearLayout(this);
        hello.setOrientation(LinearLayout.VERTICAL);
        greeting = text(40, pal.text, "sans-serif-medium");
        date = text(16, pal.sub, "sans-serif");
        hello.addView(greeting);
        hello.addView(date);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.setMargins(px(56), px(72), 0, 0);
        top.addView(hello, hl);

        LinearLayout cards = new LinearLayout(this);
        cards.setClipChildren(false);
        FrameLayout.LayoutParams cdl = new FrameLayout.LayoutParams(-1, px(206), Gravity.TOP);
        cdl.setMargins(px(48), px(148), px(48), 0);
        top.addView(cards, cdl);

        // Tarjeta de partidos: fila que se desplaza a los lados.
        LinearLayout matchCard = card();
        matchCard.setOrientation(LinearLayout.VERTICAL);
        matchCard.setClipChildren(false);
        TextView mt = text(13, pal.sub, "sans-serif-medium");
        mt.setText("Partidos · Emelec, Real Madrid y Champions");
        matchCard.addView(mt);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setPadding(0, px(8), 0, 0);
        matchRow = new LinearLayout(this);
        matchRow.setClipChildren(false);
        matchRow.setClipToPadding(false);
        matchRow.setPadding(px(4), px(4), px(4), 0);
        hs.addView(matchRow);
        matchCard.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        matchEmpty = text(14, pal.sub, "sans-serif");
        matchEmpty.setText("Buscando partidos…");
        matchCard.addView(matchEmpty);
        cards.addView(matchCard, new LinearLayout.LayoutParams(0, -1, 1f));

        LinearLayout weatherCard = card();
        weatherCard.setOrientation(LinearLayout.VERTICAL);
        LinearLayout wTop = new LinearLayout(this);
        LinearLayout wLeft = new LinearLayout(this);
        wLeft.setOrientation(LinearLayout.VERTICAL);
        wCity = text(13, pal.sub, "sans-serif-medium");
        wCity.setText("Clima");
        wTemp = text(42, pal.text, "sans-serif-light");
        wTemp.setIncludeFontPadding(false);
        wTemp.setText("--°");
        wLeft.addView(wCity);
        wLeft.addView(wTemp);
        wTop.addView(wLeft, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout wRight = new LinearLayout(this);
        wRight.setOrientation(LinearLayout.VERTICAL);
        wRight.setGravity(Gravity.END);
        wIcon = text(28, pal.text, "sans-serif");
        wDesc = text(12, pal.sub, "sans-serif");
        wDesc.setGravity(Gravity.END);
        wRight.addView(wIcon);
        wRight.addView(wDesc);
        wTop.addView(wRight);
        weatherCard.addView(wTop);
        View line = new View(this);
        line.setBackgroundColor(pal.light ? 0x1A000000 : 0x1FFFFFFF);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
        ll.setMargins(0, px(10), 0, px(8));
        weatherCard.addView(line, ll);
        LinearLayout hours = new LinearLayout(this);
        for (int i = 0; i < Weather.HOURS; i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            wHours[i][0] = text(11, pal.sub, "sans-serif-medium");
            wHours[i][1] = text(16, pal.text, "sans-serif");
            wHours[i][2] = text(12, pal.text, "sans-serif-medium");
            for (TextView t : wHours[i]) col.addView(t);
            hours.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        }
        weatherCard.addView(hours);
        LinearLayout.LayoutParams wcl = new LinearLayout.LayoutParams(px(300), -1);
        wcl.leftMargin = px(16);
        cards.addView(weatherCard, wcl);

        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(px(10), px(14), px(10), px(4));
        dock.setBackground(round(pal.card, px(28)));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-1, px(118), Gravity.BOTTOM);
        dl.setMargins(px(48), 0, px(48), px(26));
        top.addView(dock, dl);

        // Debajo del dock, todas las apps (se llega bajando).
        TextView appsTitle = text(19, pal.sub, "sans-serif-medium");
        appsTitle.setText("Apps");
        LinearLayout.LayoutParams atl = new LinearLayout.LayoutParams(-2, -2);
        atl.setMargins(px(66), px(8), 0, px(14));
        content.addView(appsTitle, atl);
        appsGrid = new LinearLayout(this);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        appsGrid.setClipChildren(false);
        appsGrid.setClipToPadding(false);
        content.addView(appsGrid, new LinearLayout.LayoutParams(-1, -2));
        return homeScroll;
    }

    private View buildSettingsPage() {
        ScrollView s = new ScrollView(this);
        s.setVerticalScrollBarEnabled(false);
        s.setClipChildren(false);
        s.setPadding(0, px(96), 0, 0);
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        settingsList.setGravity(Gravity.CENTER_HORIZONTAL);
        settingsList.setClipChildren(false);
        settingsList.setClipToPadding(false);
        settingsList.setPadding(0, px(8), 0, px(40));
        s.addView(settingsList, new FrameLayout.LayoutParams(-1, -2));

        wifiRow = settingRow("Wi-Fi", v -> {
            if (!startSafe(new Intent(Settings.ACTION_WIFI_SETTINGS))) startSafe(new Intent(Settings.ACTION_SETTINGS));
        });
        settingRow("Ajustes de Fire TV", v -> startSafe(new Intent(Settings.ACTION_SETTINGS)));
        settingRow("Abrir el inicio de Amazon", v -> openAmazonHome());
        settingRow(pal.light ? "Tema: claro" : "Tema: oscuro", v -> {
            prefs.edit().putString("theme", pal.light ? "dark" : "light").apply();
            recreate();
        });
        hiddenRow = settingRow("Mostrar apps ocultas", v -> {
            prefs.edit().remove("hidden").apply();
            toast("Apps ocultas visibles otra vez");
            rebuild();
        });
        TextView about = text(13, pal.sub, "sans-serif");
        about.setText("Inicio ATV 2.4 · github.com/Josuerti/firetv-inicio-atv");
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
        al.topMargin = px(18);
        settingsList.addView(about, al);
        return s;
    }

    private TextView settingRow(String label, View.OnClickListener click) {
        TextView t = text(17, pal.text, "sans-serif-medium");
        t.setText(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(px(24), 0, px(24), 0);
        t.setFocusable(true);
        t.setBackground(round(pal.card, px(16)));
        t.setOnFocusChangeListener((v, has) -> {
            v.setBackground(round(has ? (pal.light ? 0xFFFFFFFF : 0x4DFFFFFF) : pal.card, px(16)));
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f)
                    .translationZ(has ? px(8) : 0).setDuration(150).start();
        });
        t.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_DPAD_UP && ev.getAction() == KeyEvent.ACTION_DOWN
                    && settingsList.indexOfChild(v) == 0) {
                tabs[PAGE_SETTINGS].requestFocus();
                return true;
            }
            return false;
        });
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(560), px(56));
        lp.bottomMargin = px(12);
        settingsList.addView(t, lp);
        return t;
    }

    // ---------------------------------------------------------------- páginas y foco

    private void showPage(int p) {
        page = p;
        for (int i = 0; i < 2; i++) pages[i].setVisibility(i == p ? View.VISIBLE : View.GONE);
        tabBar.setAlpha(1f);
        clock.setAlpha(1f);
        styleTabs();
    }

    /** Con foco: pastilla sólida. Seleccionada sin foco: pastilla suave. Resto: sin fondo. */
    private void styleTabs() {
        for (int i = 0; i < 2; i++) {
            boolean sel = i == page, focus = tabs[i].isFocused();
            int bg = focus ? (pal.light ? 0xFFFFFFFF : 0xF2FFFFFF) : sel ? pal.tabSel : 0;
            tabs[i].setBackground(bg == 0 ? null : round(bg, px(TAB_H / 2f)));
            tabs[i].setTextColor(focus && !pal.light ? 0xFF1C1C1E : sel || focus ? pal.text : pal.tabText);
        }
    }

    private View pageFirst(int p) {
        if (p == PAGE_HOME) {
            if (matchRow.getChildCount() > 0) return matchRow.getChildAt(0).findViewWithTag(TAG_CARD);
            return firstDock();
        }
        return settingsList.getChildAt(0);
    }

    private View firstDock() {
        return dock.getChildCount() > 0 ? dock.getChildAt(0).findViewWithTag(TAG_CARD) : null;
    }

    private void goHome() {
        showPage(PAGE_HOME);
        homeScroll.smoothScrollTo(0, 0);
        View f = getCurrentFocus();
        if (f == null || !Integer.valueOf(KIND_DOCK).equals(f.getTag(R_KIND))) {
            View c = firstDock();
            if (c != null) c.requestFocus();
        }
    }

    // ---------------------------------------------------------------- apps

    /** Tag para encontrar el recuadro enfocable dentro de cada columna. */
    private static final Object TAG_CARD = new Object();
    private static final int R_KIND = 0x7f0f0001, R_APP = 0x7f0f0002;

    private void reload() {
        io.execute(() -> {
            final List<AppEntry> list = AppEntry.query(this);
            ui.post(() -> {
                all.clear();
                all.addAll(list);
                rebuild();
            });
        });
    }

    private void rebuild() {
        if (pendingFocusPkg == null) {
            View f = getCurrentFocus();
            if (f != null && f.getTag(R_APP) instanceof AppEntry) {
                pendingFocusPkg = ((AppEntry) f.getTag(R_APP)).pkg;
                pendingFocusDock = Integer.valueOf(KIND_DOCK).equals(f.getTag(R_KIND));
            }
        }
        Map<String, AppEntry> byPkg = new HashMap<>();
        for (AppEntry a : all) byPkg.put(a.pkg, a);
        Set<String> hidden = hidden();

        dockApps.clear();
        for (String p : dockPkgs()) {
            AppEntry a = byPkg.get(p);
            if (a != null && !dockApps.contains(a) && dockApps.size() < DOCK_MAX) dockApps.add(a);
        }
        dock.removeAllViews();
        for (AppEntry a : dockApps) dock.addView(makeTile(a, KIND_DOCK, px(124), px(70)));
        if (dockApps.isEmpty()) {
            TextView hint = text(15, pal.sub, "sans-serif");
            hint.setText("Baja a Apps y pulsa Menú ☰ sobre una app para añadirla aquí");
            dock.addView(hint);
        }

        appsGrid.removeAllViews();
        int side = Math.max(0, (screenW - GRID_COLS * (px(128) + px(20))) / 2);
        LinearLayout row = null;
        int n = 0;
        for (AppEntry a : all) {
            if (hidden.contains(a.pkg) || dockApps.contains(a)) continue;
            if (n % GRID_COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(side, 0, 0, 0);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.bottomMargin = px(10);
                appsGrid.addView(row, rl);
            }
            row.addView(makeTile(a, KIND_GRID, px(128), px(72)));
            n++;
        }
        hiddenRow.setText(hidden.isEmpty() ? "No hay apps ocultas" : "Mostrar apps ocultas (" + hidden.size() + ")");

        View target = findTile(pendingFocusPkg, pendingFocusDock);
        pendingFocusPkg = null;
        if (target == null && page == PAGE_HOME) target = firstDock();
        if (target != null) target.requestFocus();
    }

    private View makeTile(final AppEntry a, final int kind, int w, int h) {
        final FrameLayout card = new FrameLayout(this);
        card.setTag(TAG_CARD);
        card.setTag(R_APP, a);
        card.setTag(R_KIND, kind);
        card.setFocusable(true);
        rounded(card, px(12));
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
            int s = Math.round(h * 0.62f);
            card.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        final View sheen = sheen(px(12), 0x66FFFFFF);
        card.addView(sheen, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.addView(card, new LinearLayout.LayoutParams(w, h));
        final TextView label = text(13, pal.text, "sans-serif-medium");
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setAlpha(0f);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(w, -2);
        ll.topMargin = px(kind == KIND_DOCK ? 8 : 10);
        col.addView(label, ll);
        int gap = kind == KIND_DOCK ? px(7) : px(10);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        cp.setMargins(gap, 0, gap, 0);
        col.setLayoutParams(cp);

        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.1f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? px(14) : 0)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            sheen.animate().alpha(has ? 1f : 0f).setDuration(170).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? px(4) : 0).setDuration(170).start();
            if (has) {
                animateGlow(a.glow);
                if (kind == KIND_DOCK) {
                    homeScroll.smoothScrollTo(0, 0);
                } else {
                    // La fila de apps con foco queda a media pantalla, entera y con su nombre visible.
                    View row = (View) col.getParent();
                    int rowTop = appsGrid.getTop() + row.getTop();
                    homeScroll.smoothScrollTo(0, Math.max(0, rowTop - px(200)));
                }
            }
        });
        card.setOnClickListener(v -> startSafe(a.launchIntent()));
        card.setOnLongClickListener(v -> {
            showMenu(a, kind == KIND_DOCK);
            return true;
        });
        card.setOnKeyListener((v, code, ev) -> {
            if (code == KeyEvent.KEYCODE_MENU) {
                if (ev.getAction() == KeyEvent.ACTION_UP) showMenu(a, kind == KIND_DOCK);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP && ev.getAction() == KeyEvent.ACTION_DOWN
                    && kind == KIND_DOCK && matchRow.getChildCount() == 0) {
                tabs[PAGE_HOME].requestFocus();
                return true;
            }
            return false;
        });
        return col;
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
            acts.add(() -> { d.remove(a.pkg); saveDock(d); refocus(null, true); });
        } else {
            labels.add("Añadir al dock");
            acts.add(() -> {
                if (d.size() >= DOCK_MAX) {
                    toast("El dock está lleno (máximo " + DOCK_MAX + "). Quita una app primero.");
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
                Uri.fromParts("package", a.pkg, null))));

        new AlertDialog.Builder(this, pal.light ? android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
        AppEntry a = null;
        for (AppEntry e : all) if (e.pkg.equals(pkg)) a = e;
        if (a == null) return null;
        View r = findCard(inDock ? dock : appsGrid, a);
        return r != null ? r : findCard(inDock ? appsGrid : dock, a);
    }

    private static View findCard(View v, AppEntry a) {
        if (v.getTag(R_APP) == a) return v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findCard(g.getChildAt(i), a);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- partidos

    /** Partidos cada 10 min (cada minuto si hay uno en vivo) y clima cada 30, solo con la pantalla a la vista. */
    private void refreshData() {
        if (!visible) return;
        long now = System.currentTimeMillis();
        if (now - weatherAt >= WEATHER_EVERY) {
            weatherAt = now;
            io.execute(() -> {
                try {
                    final Weather w = Weather.fetch();
                    ui.post(() -> showWeather(w));
                } catch (Exception e) {
                    weatherAt = 0; // sin red: se reintenta al minuto siguiente
                }
            });
        }
        if (now - matchesAt >= (liveShown ? LIVE_EVERY : MATCHES_EVERY)) {
            matchesAt = now;
            io.execute(() -> {
                final List<Matches.Match> ms = Matches.fetch(MATCH_MAX);
                ui.post(() -> {
                    if (ms.isEmpty()) {
                        matchesAt = 0;
                        if (matchRow.getChildCount() == 0) matchEmpty.setText("Sin partidos cercanos o sin conexión.");
                        return;
                    }
                    showMatches(ms);
                });
            });
        }
    }

    /** Recuadros: escudo local · hora, marcador en vivo o resultado final · escudo visitante. */
    private void showMatches(List<Matches.Match> ms) {
        View f = matchRow.findFocus();
        int focusIdx = f == null ? -1 : matchRow.indexOfChild((View) f.getParent());
        matchRow.removeAllViews();
        liveShown = false;
        final boolean h24 = DateFormat.is24HourFormat(this);
        SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", ES);
        SimpleDateFormat hour = new SimpleDateFormat(h24 ? "H:mm" : "h:mm", ES);
        Calendar today = Calendar.getInstance();
        for (final Matches.Match m : ms) {
            liveShown |= m.live();
            final FrameLayout card = new FrameLayout(this);
            card.setTag(TAG_CARD);
            card.setFocusable(true);
            rounded(card, px(12));
            card.setBackgroundColor(pal.light ? 0xFFFFFFFF : 0x26FFFFFF);

            LinearLayout face = new LinearLayout(this);
            face.setGravity(Gravity.CENTER);
            face.setPadding(px(6), 0, px(6), 0);
            face.addView(crest(m.home), new LinearLayout.LayoutParams(px(36), px(36)));
            LinearLayout mid = new LinearLayout(this);
            mid.setOrientation(LinearLayout.VERTICAL);
            mid.setGravity(Gravity.CENTER);
            boolean scored = m.live() || m.done();
            TextView big = text(scored ? 20 : 15, pal.text, "sans-serif-medium");
            TextView small = text(10, pal.sub, "sans-serif-medium");
            big.setSingleLine(true);
            if (scored) {
                big.setText(m.home.score + " - " + m.away.score);
                if (m.live()) {
                    small.setText("● EN VIVO " + m.clock);
                    small.setTextColor(0xFFFF3B30);
                } else {
                    small.setText("FINAL");
                }
            } else {
                Calendar when = Calendar.getInstance();
                when.setTimeInMillis(m.time);
                big.setText(hour.format(when.getTime()) + (h24 ? "" : when.get(Calendar.HOUR_OF_DAY) < 12 ? " am" : " pm"));
                boolean isToday = when.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                        && when.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR);
                String d = isToday ? "Hoy" : day.format(when.getTime()).replace(".", "");
                small.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
            }
            big.setGravity(Gravity.CENTER_HORIZONTAL);
            small.setGravity(Gravity.CENTER_HORIZONTAL);
            mid.addView(big, new LinearLayout.LayoutParams(-1, -2));
            mid.addView(small, new LinearLayout.LayoutParams(-1, -2));
            face.addView(mid, new LinearLayout.LayoutParams(0, -2, 1f));
            face.addView(crest(m.away), new LinearLayout.LayoutParams(px(36), px(36)));
            card.addView(face, new FrameLayout.LayoutParams(-1, -1));
            final View sheen = sheen(px(12), pal.light ? 0x26000000 : 0x80FFFFFF);
            card.addView(sheen, new FrameLayout.LayoutParams(-1, -1));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setClipChildren(false);
            col.addView(card, new LinearLayout.LayoutParams(px(150), px(84)));
            TextView title = text(12, pal.text, "sans-serif-medium");
            title.setText(m.home.name + " vs " + m.away.name);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(px(150), -2);
            tl.topMargin = px(6);
            col.addView(title, tl);
            TextView meta = text(11, pal.sub, "sans-serif");
            meta.setText(m.league);
            col.addView(meta);

            final int tint = crestColor(m.home.crest != null ? m.home.crest : m.away.crest);
            card.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).translationZ(has ? px(12) : 0)
                        .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
                sheen.animate().alpha(has ? 1f : 0f).setDuration(170).start();
                if (has) {
                    animateGlow(tint);
                    homeScroll.smoothScrollTo(0, 0);
                }
            });
            card.setOnKeyListener((v, code, ev) -> {
                if (code == KeyEvent.KEYCODE_DPAD_UP && ev.getAction() == KeyEvent.ACTION_DOWN) {
                    tabs[PAGE_HOME].requestFocus();
                    return true;
                }
                return false;
            });
            card.setOnClickListener(v -> openLink(m.link));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = px(14);
            matchRow.addView(col, lp);
        }
        matchEmpty.setVisibility(ms.isEmpty() ? View.VISIBLE : View.GONE);
        if (focusIdx >= 0 && matchRow.getChildCount() > 0) {
            matchRow.getChildAt(Math.min(focusIdx, matchRow.getChildCount() - 1)).findViewWithTag(TAG_CARD).requestFocus();
        }
    }

    private View crest(Matches.Team t) {
        if (t.crest != null) {
            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            img.setImageBitmap(t.crest);
            return img;
        }
        TextView initials = text(14, pal.sub, "sans-serif-medium");
        initials.setGravity(Gravity.CENTER);
        initials.setText(t.name.length() > 3 ? t.name.substring(0, 3).toUpperCase(ES) : t.name);
        return initials;
    }

    /** Color medio del escudo, ignorando transparente y grises, para teñir el fondo. */
    private static int crestColor(Bitmap b) {
        if (b == null) return 0xFF8E8E93;
        Bitmap s = Bitmap.createScaledBitmap(b, 12, 12, true);
        long r = 0, g = 0, bl = 0, n = 0;
        float[] hsv = new float[3];
        for (int y = 0; y < 12; y++) {
            for (int x = 0; x < 12; x++) {
                int p = s.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.25f) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
        }
        s.recycle();
        return n == 0 ? 0xFF8E8E93 : Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
    }

    /** Abre el partido en Silk (el navegador de Fire TV) o en el que haya. */
    private void openLink(String url) {
        if (url == null) return;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.setPackage("com.amazon.cloud9");
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            startSafe(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    // ---------------------------------------------------------------- reloj, clima, red

    private void updateClock() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat(DateFormat.is24HourFormat(this) ? "H:mm" : "h:mm", ES).format(now));
        String d = new SimpleDateFormat("EEEE, d 'de' MMMM", ES).format(now);
        date.setText(Character.toUpperCase(d.charAt(0)) + d.substring(1));
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "Buenas noches" : h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches");
    }

    private void showWeather(Weather w) {
        wCity.setText("➤ " + w.city);
        wTemp.setText(w.temp + "°");
        wIcon.setText(Weather.emoji(w.code, w.day));
        wDesc.setText(Weather.describe(w.code) + "\nMáx " + w.max + "°  Mín " + w.min + "°");
        for (int i = 0; i < Weather.HOURS; i++) {
            if (w.hourLabel[i] == null) continue;
            wHours[i][0].setText(w.hourLabel[i]);
            wHours[i][1].setText(Weather.emoji(w.hourCode[i], w.hourDay[i]));
            wHours[i][2].setText(w.hourTemp[i] + "°");
        }
    }

    @SuppressWarnings("deprecation")
    private void updateWifi() {
        String label;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) {
                label = "Wi-Fi · Sin conexión";
            } else if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                String ssid = null;
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
                if (wi != null) ssid = wi.getSSID();
                if (ssid != null) ssid = ssid.replace("\"", "").trim();
                label = (ssid == null || ssid.isEmpty() || ssid.contains("unknown ssid")) ? "Wi-Fi · Conectado" : "Wi-Fi · " + ssid;
            } else {
                label = "Ethernet · Conectado";
            }
        } catch (Exception e) {
            label = "Wi-Fi";
        }
        wifiRow.setText(label);
    }

    // ---------------------------------------------------------------- acciones

    /**
     * Fire OS a veces apaga los servicios de accesibilidad (actualizaciones, cierre forzado).
     * Si tenemos WRITE_SECURE_SETTINGS (concedido por adb), volvemos a encender el nuestro.
     */
    private void ensureHomeService() {
        String me = getPackageName() + "/" + HomeService.class.getName();
        try {
            String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (on != null && on.contains(me)) return;
            String value = (on == null || on.isEmpty() || on.equals("null")) ? me : on + ":" + me;
            Settings.Secure.putString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
            Settings.Secure.putInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException ignored) {
            // Sin el permiso: se activa a mano en Ajustes → Accesibilidad.
        }
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

    // ---------------------------------------------------------------- color y utilidades

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

    /** Resplandor abajo y, además, todo el fondo teñido suave del color de la app o escudo con foco. */
    private void applyGlow(int c) {
        int rgb = c & 0x00FFFFFF;
        glowBg.setColors(new int[]{rgb | (pal.glowAlpha << 24), rgb | ((pal.glowAlpha / 4) << 24), 0x00000000});
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        hsv[1] *= 0.75f;
        int soft = Color.HSVToColor(hsv);
        rootBg.setColors(pal.light
                ? new int[]{mix(pal.bgTop, soft, 0.10f), mix(pal.bgBottom, soft, 0.30f)}
                : new int[]{mix(pal.bgTop, soft, 0.16f), mix(pal.bgBottom, soft, 0.28f)});
    }

    private static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private void rounded(View v, final int radius) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
    }

    /** Borde fino y brillo de cristal que aparecen con el foco. */
    private View sheen(int radius, int stroke) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
        g.setCornerRadius(radius);
        g.setStroke(Math.max(1, px(1)), stroke);
        View v = new View(this);
        v.setBackground(g);
        v.setAlpha(0f);
        return v;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setPadding(px(18), px(14), px(18), px(14));
        c.setBackground(round(pal.card, px(22)));
        return c;
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

    private int px(float v) {
        return Math.round(v * dp);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
