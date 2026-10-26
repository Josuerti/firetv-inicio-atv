package com.josue.atvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Fire OS no deja cambiar la pantalla de inicio por defecto. Este servicio detecta cuándo
 * aparece la portada de Amazon (botón Inicio, arranque, al salir de la tienda) y abre Inicio ATV
 * encima. Solo mira el nombre de la app en primer plano y, dentro de la home de Amazon, qué
 * pestaña de navegación está a la vista; no lee ni guarda nada más.
 */
public class HomeService extends AccessibilityService {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";

    /** true tras pulsar "Abrir el inicio de Amazon": deja usar la home de Amazon hasta abrir otra app. */
    static volatile boolean allowAmazon = false;

    /**
     * La Appstore (y los resultados de Alexa) se muestran DENTRO de la home de Amazon: al abrirlos,
     * la home de Amazon aparece justo después. Hasta este instante no la tapamos.
     */
    static volatile long allowUntil;
    private static final long DEEP_LINK_MS = 4000;
    /** Apps que abren secciones dentro de la home de Amazon. */
    static final Set<String> OPENS_AMAZON_HOME = new HashSet<>(Arrays.asList(
            "com.amazon.venezia",  // Appstore
            "com.amazon.vizzini")); // resultados de Alexa

    /** Pestañas de navegación de la home de Amazon (testTags de Compose). */
    private static final String TAB_AMAZON_FRONT = "nav-tab-movies_and_tv_home_tab";
    private static final String TAB_APPSTORE = "nav-tab-apps_categories_tab";

    static void allowDeepLink() {
        allowUntil = SystemClock.uptimeMillis() + DEEP_LINK_MS;
    }

    private long lastJump;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable scan = () -> {
        if (AMAZON_HOME.equals(HomeService.this.front) && !allowAmazon && !deepLink() && showsAmazonFront()) jump();
    };
    /** App que está delante (sin contar teclado, volumen, etc.). */
    private String front = "";

    @Override
    protected void onServiceConnected() {
        // Al arrancar la TV el servicio llega tarde: si la home de Amazon ya está delante, la tapamos.
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && AMAZON_HOME.equals(String.valueOf(root.getPackageName()))) jump();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        int type = e.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Dentro de la home de Amazon: ¿se fue de la tienda a la portada? (sin cambio de ventana)
            // Miramos cuando la pantalla se asienta: a los 0,6 s y otra vez a los 1,6 s.
            if (AMAZON_HOME.equals(pkg) && AMAZON_HOME.equals(front) && !allowAmazon && !deepLink()) {
                handler.removeCallbacks(scan);
                handler.postDelayed(scan, 600);
                handler.postDelayed(scan, 1600);
            }
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;

        if (!isOverlay(pkg)) front = pkg;
        if (AMAZON_HOME.equals(pkg)) {
            String cls = e.getClassName() == null ? "" : e.getClassName().toString();
            if (!allowAmazon && !deepLink() && cls.contains("HomeActivity")) jump();
        } else if (OPENS_AMAZON_HOME.contains(pkg)) {
            allowDeepLink();
        } else if (!pkg.equals(getPackageName()) && !isOverlay(pkg)) {
            allowAmazon = false;
        }
    }

    private static boolean deepLink() {
        return SystemClock.uptimeMillis() < allowUntil;
    }

    /** true si se ve la pestaña de portada de Amazon y no la de la tienda. */
    private boolean showsAmazonFront() {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return false;
        }
        if (root == null || !AMAZON_HOME.equals(String.valueOf(root.getPackageName()))) return false;
        boolean front = false;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen++ < 400) {
            AccessibilityNodeInfo n = q.poll();
            String id = n.getViewIdResourceName();
            if (TAB_APPSTORE.equals(id)) return false;
            if (TAB_AMAZON_FRONT.equals(id)) front = true;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return front;
    }

    /** Teclado, barra de volumen, avisos: no cuentan como "abrir otra app". */
    private static boolean isOverlay(String pkg) {
        return pkg.equals("com.android.systemui") || pkg.equals("com.amazon.tv.ime")
                || pkg.equals("android") || pkg.startsWith("com.amazon.tv.quicksettings");
    }

    private void jump() {
        long now = SystemClock.uptimeMillis();
        if (now - lastJump < 700) return;
        lastJump = now;
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        i.putExtra(HomeActivity.EXTRA_FROM_HOME_KEY, true);
        try {
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onInterrupt() {
    }
}
