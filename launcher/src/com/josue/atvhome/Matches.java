package com.josue.atvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Partidos de Emelec, Real Madrid y la Champions League desde la API pública de ESPN
 * (la misma de EcuaScore): en vivo, resultados de las últimas 30 h y próximos.
 * Todo se lee en streaming; los días sin partidos pesan ~2 KB y los escudos 96 px ~9 KB.
 */
final class Matches {
    static final String EMELEC = "2668", REAL_MADRID = "86";
    private static final Set<String> FAVS = new HashSet<>(Arrays.asList(EMELEC, REAL_MADRID));
    private static final String UCL = "uefa.champions";
    private static final long FINAL_WINDOW_MS = 30L * 60 * 60 * 1000;

    static final class Team {
        final String id, name, logo, score;
        Bitmap crest;

        Team(String id, String name, String logo, String score) {
            this.id = id;
            this.name = name;
            this.logo = logo;
            this.score = score;
        }
    }

    static final class Match {
        final String id, league, link, state, clock;
        final long time;
        final Team home, away;

        Match(String id, long time, String league, String link, String state, String clock, Team home, Team away) {
            this.id = id;
            this.time = time;
            this.league = league;
            this.link = link;
            this.state = state;
            this.clock = clock;
            this.home = home;
            this.away = away;
        }

        boolean live() {
            return "in".equals(state);
        }

        boolean done() {
            return "post".equals(state);
        }

        boolean fav() {
            return FAVS.contains(home.id) || FAVS.contains(away.id);
        }
    }

    static List<Match> fetch(int max) {
        Map<String, Match> byId = new LinkedHashMap<>();
        String yesterday = day(-1), today = day(0);

        // En vivo y resultados recientes de Emelec (LigaPro) y Real Madrid (LaLiga).
        for (String lg : new String[]{"ecu.1", "esp.1"}) {
            for (String d : new String[]{yesterday, today}) {
                for (Match m : scoreboard(lg, d)) if (m.fav()) byId.put(m.id, m);
            }
        }
        // Champions: ayer, hoy y, si no hay nada por jugar, el siguiente día con partidos.
        boolean uclAhead = false;
        for (String d : new String[]{yesterday, today}) {
            for (Match m : scoreboard(UCL, d)) {
                byId.put(m.id, m);
                uclAhead |= !m.done();
            }
        }
        for (int k = 1; k <= 10 && !uclAhead; k++) {
            List<Match> day = scoreboard(UCL, day(k));
            if (day.isEmpty()) continue;
            for (Match m : day) byId.put(m.id, m);
            for (Match m : scoreboard(UCL, day(k + 1))) byId.put(m.id, m); // las jornadas son de 2 días
            uclAhead = true;
        }
        // Próximo partido de cada favorito, en cualquier torneo.
        for (String team : FAVS) {
            for (Match m : teamNext(team)) if (!byId.containsKey(m.id)) byId.put(m.id, m);
        }

        long now = System.currentTimeMillis();
        List<Match> all = new ArrayList<>();
        for (Match m : byId.values()) if (!m.done() || now - m.time < FINAL_WINDOW_MS) all.add(m);
        Collections.sort(all, (a, b) -> {
            int ra = rank(a), rb = rank(b);
            if (ra != rb) return ra - rb;
            if (a.fav() != b.fav()) return a.fav() ? -1 : 1;
            return a.done() ? Long.compare(b.time, a.time) : Long.compare(a.time, b.time);
        });
        List<Match> out = new ArrayList<>(all.subList(0, Math.min(max, all.size())));
        Map<String, Bitmap> crests = new HashMap<>();
        for (Match m : out) {
            for (Team t : new Team[]{m.home, m.away}) {
                if (!crests.containsKey(t.id)) crests.put(t.id, crest(t.logo));
                t.crest = crests.get(t.id);
            }
        }
        return out;
    }

    /** En vivo primero, luego próximos, luego finales. */
    private static int rank(Match m) {
        return m.live() ? 0 : m.done() ? 2 : 1;
    }

    private static String day(int offset) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, offset);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(c.getTime());
    }

    private static List<Match> scoreboard(String league, String date) {
        try {
            return events("https://site.api.espn.com/apis/site/v2/sports/soccer/" + league
                    + "/scoreboard?dates=" + date, league, 40);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<Match> teamNext(String team) {
        try {
            List<Match> l = events("https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/"
                    + team + "/schedule?fixture=true", null, 1);
            return l;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** Lee el array "events" en streaming y corta la descarga al tener {@code want} partidos. */
    private static List<Match> events(String url, String league, int want) throws Exception {
        HttpURLConnection http = open(url);
        List<Match> out = new ArrayList<>();
        try (InputStream in = http.getInputStream()) {
            JsonReader r = new JsonReader(new InputStreamReader(in, "UTF-8"));
            r.beginObject();
            while (r.hasNext()) {
                if (!"events".equals(r.nextName())) {
                    r.skipValue();
                    continue;
                }
                r.beginArray();
                while (r.hasNext() && out.size() < want) {
                    Match m = parse((JSONObject) read(r), league);
                    if (m != null) out.add(m);
                }
                break;
            }
        } finally {
            http.disconnect();
        }
        return out;
    }

    private static Match parse(JSONObject e, String leagueSlug) {
        try {
            JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
            JSONArray comps = c.getJSONArray("competitors");
            Team home = null, away = null;
            for (int i = 0; i < comps.length(); i++) {
                JSONObject cp = comps.getJSONObject(i);
                JSONObject t = cp.getJSONObject("team");
                String id = t.optString("id");
                String name = EMELEC.equals(id) ? "Emelec" : t.optString("shortDisplayName", t.optString("displayName"));
                String logo = t.optString("logo", null);
                JSONArray logos = t.optJSONArray("logos");
                if (logo == null && logos != null && logos.length() > 0) logo = logos.getJSONObject(0).optString("href");
                Object sc = cp.opt("score"); // texto en el marcador, objeto en el calendario
                String score = sc instanceof JSONObject ? ((JSONObject) sc).optString("displayValue")
                        : sc instanceof String ? (String) sc : null;
                Team team = new Team(id, name, logo, score);
                if ("home".equals(cp.optString("homeAway"))) home = team;
                else away = team;
            }
            if (home == null || away == null) return null;
            JSONObject st = c.optJSONObject("status");
            if (st == null) st = e.optJSONObject("status");
            JSONObject type = st == null ? null : st.optJSONObject("type");
            String state = type == null ? "pre" : type.optString("state", "pre");
            String clock = st == null ? "" : st.optString("displayClock", "");
            String lg;
            if (UCL.equals(leagueSlug)) lg = "Champions";
            else if ("ecu.1".equals(leagueSlug)) lg = "LigaPro";
            else if ("esp.1".equals(leagueSlug)) lg = "LaLiga";
            else {
                JSONObject league = e.optJSONObject("league");
                lg = pretty(league == null ? "" : league.optString("abbreviation", league.optString("name", "")));
            }
            JSONArray links = e.optJSONArray("links");
            String link = links != null && links.length() > 0 ? links.getJSONObject(0).optString("href") : null;
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            long time = iso.parse(e.getString("date")).getTime();
            return new Match(e.getString("id"), time, lg, link, state, clock, home, away);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String pretty(String lg) {
        if (lg.contains("Champions")) return "Champions";
        if (lg.contains("Friendly")) return "Amistoso";
        if (lg.contains("LigaPro")) return "LigaPro";
        if (lg.contains("Libertadores")) return "Libertadores";
        if (lg.contains("Sudamericana")) return "Sudamericana";
        if (lg.equalsIgnoreCase("LALIGA")) return "LaLiga";
        if (lg.contains("Copa del Rey")) return "Copa del Rey";
        return lg;
    }

    /** Convierte el valor JSON actual del lector en JSONObject/JSONArray/String/Boolean. */
    private static Object read(JsonReader r) throws Exception {
        JsonToken t = r.peek();
        switch (t) {
            case BEGIN_OBJECT: {
                JSONObject o = new JSONObject();
                r.beginObject();
                while (r.hasNext()) o.put(r.nextName(), read(r));
                r.endObject();
                return o;
            }
            case BEGIN_ARRAY: {
                JSONArray a = new JSONArray();
                r.beginArray();
                while (r.hasNext()) a.put(read(r));
                r.endArray();
                return a;
            }
            case BOOLEAN:
                return r.nextBoolean();
            case NULL:
                r.nextNull();
                return JSONObject.NULL;
            default:
                return r.nextString();
        }
    }

    private static Bitmap crest(String logo) {
        if (logo == null) return null;
        String path = logo.replaceFirst("^https?://a\\.espncdn\\.com", "");
        try {
            HttpURLConnection http = open("https://a.espncdn.com/combiner/i?img=" + path + "&h=96&w=96");
            try (InputStream in = http.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            } finally {
                http.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(8000);
        http.setReadTimeout(8000);
        http.setRequestProperty("User-Agent", "InicioATV");
        return http;
    }
}
