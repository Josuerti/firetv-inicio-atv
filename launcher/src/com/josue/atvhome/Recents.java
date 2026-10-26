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

    static final class Item {
        final String pkg;
        final long time;

        Item(String pkg, long time) {
            this.pkg = pkg;
            this.time = time;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Item && ((Item) o).pkg.equals(pkg) && ((Item) o).time == time;
        }

        @Override
        public int hashCode() {
            return pkg.hashCode() * 31 + (int) time;
        }
    }

    static boolean granted(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
    }

    static List<Item> query(Context c) {
        if (!granted(c)) return Collections.emptyList();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return Collections.emptyList();
        long now = System.currentTimeMillis();
        Map<String, Long> last = new HashMap<>();
        UsageEvents events = usm.queryEvents(now - WINDOW_MS, now);
        UsageEvents.Event e = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last.put(e.getPackageName(), e.getTimeStamp());
        }
        List<Item> items = new ArrayList<>();
        for (Map.Entry<String, Long> en : last.entrySet()) items.add(new Item(en.getKey(), en.getValue()));
        Collections.sort(items, (a, b) -> Long.compare(b.time, a.time));
        return items;
    }

    /** "Hace 5 min", "Hace 3 h", "Ayer", "Hace 4 días". */
    static String ago(long time) {
        long m = (System.currentTimeMillis() - time) / 60000;
        if (m < 1) return "Ahora mismo";
        if (m < 60) return "Hace " + m + " min";
        long h = m / 60;
        if (h < 24) return "Hace " + h + " h";
        long d = h / 24;
        return d == 1 ? "Ayer" : "Hace " + d + " días";
    }
}
