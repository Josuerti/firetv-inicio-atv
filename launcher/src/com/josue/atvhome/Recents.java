package com.josue.atvhome;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apps abiertas recientemente (últimos 7 días), de la más reciente a la más antigua.
 * Necesita el permiso de acceso al uso: adb shell appops set com.josue.atvhome GET_USAGE_STATS allow
 */
final class Recents {
    private static final long WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    static boolean granted(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static List<String> query(Context c) {
        if (!granted(c)) return Collections.emptyList();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return Collections.emptyList();
        long now = System.currentTimeMillis();
        final Map<String, Long> last = new HashMap<>();
        UsageEvents events = usm.queryEvents(now - WINDOW_MS, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last.put(e.getPackageName(), e.getTimeStamp());
        }
        List<String> pkgs = new ArrayList<>(last.keySet());
        Collections.sort(pkgs, (a, b) -> Long.compare(last.get(b), last.get(a)));
        return pkgs;
    }
}
