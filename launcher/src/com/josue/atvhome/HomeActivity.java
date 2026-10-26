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

/**
 * Pantalla de inicio estilo tvOS: pestañas Inicio / Apps / Ajustes, saludo, tarjetas de
 * Recientes y Clima, y dock. Tema claro u oscuro.
 */
public class HomeActivity extends Activity {
    static final String EXTRA_FROM_HOME_KEY = "from_home_key";
    static final int DOCK_MAX = 6, GRID_COLS = 6, RECENT_MAX = 3;
    static final String[] DEFAULT_DOCK = {
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.firetv.youtube",
            "com.crunchyroll.crunchyroid",
            "com.stremio.one",
            "com.cbs.ca"};
    static final Locale ES = new Locale("es", "ES");
    static final int PAGE_HOME = 0, PAGE_APPS = 1, PAGE_SETTINGS = 2;
    static final int KIND_DOCK = 0, KIND_RECENT = 1, KIND_GRID = 2;

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
            tabSel = light ? 0xFFFFFFFF : 0x40FFFFFF;
            tabText = light ? 0xFF3A3A3C : 0xFFEBEBF0;
            glowAlpha = light ? 0x38 : 0x5C;
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private Palette pal;
    private float dp;
    private int screenW;

    private GradientDrawable glowBg;
    private int glowColor = 0xFF8E8E93;
    private ValueAnimator glowAnim;

    private final TextView[] tabs = new TextView[3];
    private final View[] pages = new View[3];
    private int page = PAGE_HOME;

    private TextView clock, greeting, date, wifiRow, themeRow, hiddenRow, recentEmpty, cardTitle;
    private LinearLayout dock, recentRow, newsList, appsGrid, settingsList;
    /** La tarjeta grande muestra noticias (por defecto) o apps recientes. */
    private boolean newsMode;
    private long newsAt;
    private ScrollView appsScroll;
    // Tarjeta de clima
    private TextView wCity, wIcon, wTemp, wDesc;
    private final TextView[][] wHours = new TextView[Weather.HOURS][3];
    private long weatherAt;

    private final List<AppEntry> all = new ArrayList<>();
    private final List<AppEntry> dockApps = new ArrayList<>();
    private final List<Recents.Item> recents = new ArrayList<>();
    private String pendingFocusPkg;
    private boolean pendingFocusDock = true;
    private boolean released;

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        screenW = getResources().getDisplayMetrics().widthPixels;
        prefs = getSharedPreferences("atv", MODE_PRIVATE);
        pal = new Palette(!"dark".equals(prefs.getString("theme", "light")));
        newsMode = !"recents".equals(prefs.getString("card", "news"));
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
        updateClock();
        updateWifi();
        refreshWeather();
        if (newsMode) refreshNews();
        else if (!all.isEmpty()) refreshRecents();
        ensureHomeService();
    }

    /** Al irnos a segundo plano soltamos banners e iconos: la RAM es para la app que estás viendo. */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN && !released) {
            released = true;
            dock.removeAllViews();
            recentRow.removeAllViews();
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
            refreshWeather();
            if (newsMode) refreshNews();
        }
    };

    // ---------------------------------------------------------------- estructura

    private View buildChrome() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{pal.bgTop, pal.bgBottom}));

        glowBg = new GradientDrawable();
        glowBg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowBg.setGradientRadius(px(620));
        glowBg.setGradientCenter(0.5f, 1.05f);
        applyGlow(glowColor);
        View glow = new View(this);
        glow.setBackground(glowBg);
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        // Barra superior: pestañas centradas y reloj a la derecha.
        LinearLayout tabBar = new LinearLayout(this);
        tabBar.setPadding(px(5), px(5), px(5), px(5));
        tabBar.setBackground(round(pal.tabBar, px(24)));
        tabBar.setClipChildren(false);
        String[] names = {"Inicio", "Apps", "Ajustes"};
        for (int i = 0; i < 3; i++) {
            final int p = i;
            TextView t = text(15, pal.tabText, "sans-serif-medium");
            t.setText(names[i]);
            t.setPadding(px(20), px(7), px(20), px(7));
            t.setFocusable(true);
            t.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(150).start();
                if (has) showPage(p);
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

        pages[PAGE_HOME] = buildHomePage();
        pages[PAGE_APPS] = buildAppsPage();
        pages[PAGE_SETTINGS] = buildSettingsPage();
        for (View p : pages) root.addView(p, new FrameLayout.LayoutParams(-1, -1));
        return root;
    }

    private View buildHomePage() {
        FrameLayout home = new FrameLayout(this);
        home.setClipChildren(false);

        LinearLayout hello = new LinearLayout(this);
        hello.setOrientation(LinearLayout.VERTICAL);
        greeting = text(40, pal.text, "sans-serif-medium");
        date = text(16, pal.sub, "sans-serif");
        hello.addView(greeting);
        hello.addView(date);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        hl.setMargins(px(56), px(72), 0, 0);
        home.addView(hello, hl);

        // Tarjetas: Recientes (izquierda) y Clima (derecha).
        LinearLayout cards = new LinearLayout(this);
        cards.setClipChildren(false);
        FrameLayout.LayoutParams cdl = new FrameLayout.LayoutParams(-1, px(196), Gravity.TOP);
        cdl.setMargins(px(48), px(152), px(48), 0);
        home.addView(cards, cdl);

        LinearLayout recentCard = card();
        recentCard.setOrientation(LinearLayout.VERTICAL);
        recentCard.setClipChildren(false);
        cardTitle = text(13, pal.sub, "sans-serif-medium");
        cardTitle.setText(newsMode ? "Noticias · " + News.SOURCE : "Recientes");
        recentCard.addView(cardTitle);
        recentRow = new LinearLayout(this);
        recentRow.setClipChildren(false);
        recentRow.setClipToPadding(false);
        LinearLayout.LayoutParams rrl = new LinearLayout.LayoutParams(-1, -2);
        rrl.topMargin = px(10);
        recentCard.addView(recentRow, rrl);
        newsList = new LinearLayout(this);
        newsList.setOrientation(LinearLayout.VERTICAL);
        newsList.setClipChildren(false);
        newsList.setClipToPadding(false);
        LinearLayout.LayoutParams nll = new LinearLayout.LayoutParams(-1, -2);
        nll.topMargin = px(4);
        recentCard.addView(newsList, nll);
        recentEmpty = text(14, pal.sub, "sans-serif");
        recentEmpty.setText(newsMode ? "Cargando titulares…" : "Las apps que abras aparecerán aquí.");
        recentCard.addView(recentEmpty);
        recentRow.setVisibility(newsMode ? View.GONE : View.VISIBLE);
        newsList.setVisibility(newsMode ? View.VISIBLE : View.GONE);
        cards.addView(recentCard, new LinearLayout.LayoutParams(0, -1, 1f));

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

        // Dock.
        dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(px(10), px(14), px(10), px(4));
        dock.setBackground(round(pal.card, px(28)));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-1, px(118), Gravity.BOTTOM);
        dl.setMargins(px(48), 0, px(48), px(26));
        home.addView(dock, dl);
        return home;
    }

    private View buildAppsPage() {
        appsScroll = new ScrollView(this);
        appsScroll.setVerticalScrollBarEnabled(false);
        appsScroll.setClipChildren(false);
        appsScroll.setClipToPadding(false);
        appsScroll.setPadding(0, px(84), 0, 0);
        appsGrid = new LinearLayout(this);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        appsGrid.setClipChildren(false);
        appsGrid.setClipToPadding(false);
        appsGrid.setPadding(0, px(16), 0, px(40));
        appsScroll.addView(appsGrid, new FrameLayout.LayoutParams(-1, -2));
        return appsScroll;
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
        themeRow = settingRow(pal.light ? "Tema: claro" : "Tema: oscuro", v -> {
            prefs.edit().putString("theme", pal.light ? "dark" : "light").apply();
            recreate();
        });
        settingRow(newsMode ? "Tarjeta de inicio: noticias" : "Tarjeta de inicio: apps recientes", v -> {
            prefs.edit().putString("card", newsMode ? "recents" : "news").apply();
            recreate();
        });
        hiddenRow = settingRow("Mostrar apps ocultas", v -> {
            prefs.edit().remove("hidden").apply();
            toast("Apps ocultas visibles otra vez");
            rebuild();
        });
        TextView about = text(13, pal.sub, "sans-serif");
        about.setText("Inicio ATV 2.1 · github.com/Josuerti/firetv-inicio-atv");
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
        for (int i = 0; i < 3; i++) pages[i].setVisibility(i == p ? View.VISIBLE : View.GONE);
        styleTabs();
    }

    private void styleTabs() {
        for (int i = 0; i < 3; i++) {
            boolean sel = i == page;
            tabs[i].setBackground(sel ? round(pal.tabSel, px(20)) : null);
            tabs[i].setTextColor(sel ? pal.text : pal.tabText);
            tabs[i].setTranslationZ(sel && pal.light ? px(3) : 0);
        }
    }

    private View pageFirst(int p) {
        if (p == PAGE_HOME) {
            if (newsMode && newsList.getChildCount() > 0) return newsList.getChildAt(0);
            if (!newsMode && recentRow.getChildCount() > 0) return recentRow.getChildAt(0).findViewWithTag(TAG_CARD);
            return dock.getChildCount() > 0 ? dock.getChildAt(0).findViewWithTag(TAG_CARD) : null;
        }
        if (p == PAGE_APPS) {
            if (appsGrid.getChildCount() == 0) return null;
            View row = appsGrid.getChildAt(0);
            return ((LinearLayout) row).getChildCount() > 0 ? ((LinearLayout) row).getChildAt(0).findViewWithTag(TAG_CARD) : null;
        }
        return settingsList.getChildAt(0);
    }

    private void goHome() {
        showPage(PAGE_HOME);
        View f = getCurrentFocus();
        if (f == null || !(f.getTag(R_KIND) instanceof Integer) || (Integer) f.getTag(R_KIND) != KIND_DOCK) {
            if (dock.getChildCount() > 0) {
                View c = dock.getChildAt(0).findViewWithTag(TAG_CARD);
                if (c != null) c.requestFocus();
            }
        }
    }

    // ---------------------------------------------------------------- apps

    /** Tag para encontrar el recuadro enfocable dentro de cada columna. */
    private static final Object TAG_CARD = new Object();
    private static final int R_KIND = 0x7f0f0001, R_APP = 0x7f0f0002;

    private void reload() {
        io.execute(() -> {
            final List<AppEntry> list = AppEntry.query(this);
            final List<Recents.Item> rec = Recents.query(this);
            ui.post(() -> {
                all.clear();
                all.addAll(list);
                recents.clear();
                recents.addAll(rec);
                rebuild();
            });
        });
    }

    private void refreshRecents() {
        io.execute(() -> {
            final List<Recents.Item> rec = Recents.query(this);
            ui.post(() -> {
                if (rec.equals(recents)) return;
                recents.clear();
                recents.addAll(rec);
                buildRecents();
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
        for (AppEntry a : dockApps) dock.addView(makeTile(a, KIND_DOCK, px(124), px(70), null));
        if (dockApps.isEmpty()) {
            TextView hint = text(15, pal.sub, "sans-serif");
            hint.setText("Ve a Apps y pulsa Menú ☰ sobre una app para añadirla aquí");
            dock.addView(hint);
        }

        buildRecents();

        appsGrid.removeAllViews();
        int side = Math.max(0, (screenW - GRID_COLS * (px(128) + px(20))) / 2);
        LinearLayout row = null;
        int n = 0;
        for (AppEntry a : all) {
            if (hidden.contains(a.pkg)) continue;
            if (n % GRID_COLS == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                row.setPadding(side, 0, 0, 0);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
                rl.bottomMargin = px(10);
                appsGrid.addView(row, rl);
            }
            row.addView(makeTile(a, KIND_GRID, px(128), px(72), null));
            n++;
        }
        hiddenRow.setText(hidden.isEmpty() ? "No hay apps ocultas" : "Mostrar apps ocultas (" + hidden.size() + ")");
        hiddenRow.setEnabled(!hidden.isEmpty());

        View target = findTile(pendingFocusPkg, pendingFocusDock);
        pendingFocusPkg = null;
        if (target == null && page == PAGE_HOME && dock.getChildCount() > 0) target = dock.getChildAt(0).findViewWithTag(TAG_CARD);
        if (target != null) target.requestFocus();
    }

    private void refreshNews() {
        long now = System.currentTimeMillis();
        if (now - newsAt < 30 * 60 * 1000L) return;
        newsAt = now;
        io.execute(() -> {
            try {
                final List<News.Item> items = News.fetch(3);
                ui.post(() -> showNews(items));
            } catch (Exception e) {
                newsAt = 0; // sin red: se reintenta en el siguiente minuto
                ui.post(() -> {
                    if (newsList.getChildCount() == 0) recentEmpty.setText("Sin conexión: los titulares volverán solos.");
                });
            }
        });
    }

    private void showNews(List<News.Item> items) {
        boolean hadFocus = newsList.findFocus() != null;
        newsList.removeAllViews();
        for (final News.Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setFocusable(true);
            row.setPadding(px(12), px(4), px(12), px(4));
            final TextView title = text(14, pal.text, "sans-serif-medium");
            title.setText(it.title);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setMarqueeRepeatLimit(-1);
            TextView meta = text(11, pal.sub, "sans-serif");
            meta.setText(it.time > 0 ? Recents.ago(it.time) : News.SOURCE);
            row.addView(title);
            row.addView(meta);
            row.setOnFocusChangeListener((v, has) -> {
                v.setBackground(has ? round(pal.light ? 0xFFFFFFFF : 0x33FFFFFF, px(12)) : null);
                v.setTranslationZ(has && pal.light ? px(6) : 0);
                // Con foco el titular completo se desplaza; sin foco vuelve a cortarse con "…".
                title.setEllipsize(has ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END);
                title.setSelected(has);
                if (has) animateGlow(0xFF8E8E93);
            });
            row.setOnKeyListener((v, code, ev) -> {
                if (code == KeyEvent.KEYCODE_DPAD_UP && ev.getAction() == KeyEvent.ACTION_DOWN
                        && newsList.indexOfChild(v) == 0) {
                    tabs[PAGE_HOME].requestFocus();
                    return true;
                }
                return false;
            });
            row.setOnClickListener(v -> openLink(it.link));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = px(2);
            newsList.addView(row, lp);
        }
        recentEmpty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        if (hadFocus && newsList.getChildCount() > 0) newsList.getChildAt(0).requestFocus();
    }

    /** Abre la noticia en Silk (el navegador de Fire TV) o en el que haya. */
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

    private void buildRecents() {
        if (newsMode) return;
        recentRow.removeAllViews();
        Set<String> hidden = hidden();
        for (Recents.Item it : recents) {
            AppEntry a = findApp(it.pkg);
            if (a == null || hidden.contains(it.pkg)) continue;
            recentRow.addView(makeTile(a, KIND_RECENT, px(150), px(84), Recents.ago(it.time)));
            if (recentRow.getChildCount() == RECENT_MAX) break;
        }
        boolean empty = recentRow.getChildCount() == 0;
        recentRow.setVisibility(empty ? View.GONE : View.VISIBLE);
        recentEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private View makeTile(final AppEntry a, final int kind, int w, int h, String subtitle) {
        final FrameLayout card = new FrameLayout(this);
        card.setTag(TAG_CARD);
        card.setTag(R_APP, a);
        card.setTag(R_KIND, kind);
        card.setFocusable(true);
        card.setClipToOutline(true);
        final int radius = px(12);
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
            int s = Math.round(h * 0.62f);
            card.addView(img, new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
        }
        GradientDrawable sheenBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0x40FFFFFF, 0x0AFFFFFF, 0x00FFFFFF});
        sheenBg.setCornerRadius(radius);
        sheenBg.setStroke(Math.max(1, px(1)), 0x66FFFFFF);
        final View sheen = new View(this);
        sheen.setBackground(sheenBg);
        sheen.setAlpha(0f);
        card.addView(sheen, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.addView(card, new LinearLayout.LayoutParams(w, h));
        final TextView label = text(13, pal.text, "sans-serif-medium");
        label.setText(a.label);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(w, -2);
        ll.topMargin = px(kind == KIND_DOCK ? 8 : 10);
        col.addView(label, ll);
        final boolean labelAlways = kind == KIND_RECENT;
        if (labelAlways) {
            TextView sub = text(11, pal.sub, "sans-serif");
            sub.setText(subtitle);
            col.addView(sub);
        } else {
            label.setGravity(Gravity.CENTER_HORIZONTAL);
            label.setAlpha(0f);
        }
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
        int gap = kind == KIND_DOCK ? px(7) : px(10);
        cp.setMargins(kind == KIND_RECENT ? 0 : gap, 0, kind == KIND_RECENT ? px(14) : gap, 0);
        col.setLayoutParams(cp);

        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.1f : 1f;
            v.animate().scaleX(s).scaleY(s).translationZ(has ? px(14) : 0)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            sheen.animate().alpha(has ? 1f : 0f).setDuration(170).start();
            if (!labelAlways) label.animate().alpha(has ? 1f : 0f).translationY(has ? px(4) : 0).setDuration(170).start();
            if (has) animateGlow(a.glow);
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
            if (code == KeyEvent.KEYCODE_DPAD_UP && ev.getAction() == KeyEvent.ACTION_DOWN) {
                boolean top = kind == KIND_RECENT
                        || (kind == KIND_DOCK && (newsMode ? newsList : recentRow).getChildCount() == 0)
                        || (kind == KIND_GRID && appsGrid.indexOfChild((View) col.getParent()) == 0);
                if (top) {
                    tabs[page].requestFocus();
                    return true;
                }
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
            if (i < 0) {
                labels.add("Añadir al dock");
                acts.add(() -> {
                    if (d.size() >= DOCK_MAX) {
                        toast("El dock está lleno (máximo " + DOCK_MAX + "). Quita una app primero.");
                    } else {
                        d.add(a.pkg);
                        saveDock(d);
                        refocus(a.pkg, false);
                    }
                });
            }
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
        AppEntry a = findApp(pkg);
        if (a == null) return null;
        View parent = inDock ? dock : (page == PAGE_APPS ? appsGrid : pages[PAGE_HOME]);
        return findCard(parent, a);
    }

    private static View findCard(View v, AppEntry a) {
        if (v.getTag(R_APP) == a) return v;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View r = findCard(g.getChildAt(i), a);
                if (r != null) return r;
            }
        }
        return null;
    }

    private AppEntry findApp(String pkg) {
        if (pkg == null) return null;
        for (AppEntry a : all) if (a.pkg.equals(pkg)) return a;
        return null;
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

    private void refreshWeather() {
        long now = System.currentTimeMillis();
        if (now - weatherAt < 30 * 60 * 1000L) return;
        weatherAt = now;
        io.execute(() -> {
            try {
                final Weather w = Weather.fetch();
                ui.post(() -> showWeather(w));
            } catch (Exception e) {
                weatherAt = 0; // sin red: se reintenta en el siguiente minuto
            }
        });
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

    // ---------------------------------------------------------------- utilidades

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
        glowBg.setColors(new int[]{rgb | (pal.glowAlpha << 24), rgb | ((pal.glowAlpha / 4) << 24), 0x00000000});
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
