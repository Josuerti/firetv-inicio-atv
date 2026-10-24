package com.josue.atvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Fire OS no deja cambiar la pantalla de inicio por defecto. Este servicio detecta cuándo
 * aparece la home de Amazon (botón Inicio, arranque) y abre Inicio ATV encima.
 * Solo mira el nombre del paquete/actividad en primer plano.
 */
public class HomeService extends AccessibilityService {
    static final String AMAZON_HOME = "com.amazon.tv.launcher";

    /** true tras pulsar la pastilla "Inicio Amazon": deja usar la home de Amazon hasta abrir otra app. */
    static volatile boolean allowAmazon = false;

    private long lastJump;

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
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (AMAZON_HOME.equals(pkg)) {
            String cls = e.getClassName() == null ? "" : e.getClassName().toString();
            if (!allowAmazon && cls.contains("HomeActivity")) jump();
        } else if (!pkg.equals(getPackageName()) && !isOverlay(pkg)) {
            allowAmazon = false;
        }
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
