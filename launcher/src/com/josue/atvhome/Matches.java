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
 * Próximos partidos de los equipos favoritos, desde la API pública de ESPN (la misma de EcuaScore).
 * Cada calendario se lee en streaming y se corta en cuanto hay partidos suficientes;
 * los escudos se piden ya en 96 px (~9 KB).
 */
final class Matches {
    /** Equipos que se pueden elegir: id de ESPN → nombre para mostrar. */
    static final LinkedHashMap<String, String> TEAMS = new LinkedHashMap<>();
    static {
        TEAMS.put("209", "Ecuador");
        TEAMS.put("2686", "Barcelona SC");
        TEAMS.put("2668", "Emelec");
        TEAMS.put("4816", "Liga de Quito");
        TEAMS.put("17086", "Independiente del Valle");
        TEAMS.put("86", "Real Madrid");
        TEAMS.put("83", "FC Barcelona");
        TEAMS.put("382", "Manchester City");
        TEAMS.put("364", "Liverpool");
        TEAMS.put("359", "Arsenal");
        TEAMS.put("360", "Manchester United");
        TEAMS.put("132", "Bayern Múnich");
        TEAMS.put("160", "PSG");
        TEAMS.put("111", "Juventus");
        TEAMS.put("20232", "Inter Miami");
    }
    static final String DEFAULT_FAVS = "209,2686,2668,4816,86,83";

    /** Nombres cortos propios para que no salgan dos "Barcelona". */
    private static final Map<String, String> SHORT = new HashMap<>();
    static {
        SHORT.put("2686", "Barcelona SC");
        SHORT.put("83", "FC Barcelona");
        SHORT.put("4816", "Liga de Quito");
    }

    static final class Team {
        final String id, name, logo;
        final String score;
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
    }

    static List<Match> fetch(List<String> favs, int max) {
        Map<String, Match> byId = new LinkedHashMap<>();
        for (String team : favs) {
            try {
                for (Match m : teamSchedule(team, 2)) byId.put(m.id, m);
            } catch (Exception ignored) {
                // un equipo sin red o sin calendario no tumba a los demás
            }
        }
        List<Match> all = new ArrayList<>(byId.values());
        Collections.sort(all, (a, b) -> {
            if (a.live() != b.live()) return a.live() ? -1 : 1; // en vivo primero
            return Long.compare(a.time, b.time);
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

    private static List<Match> teamSchedule(String team, int want) throws Exception {
        HttpURLConnection http = open("https://site.api.espn.com/apis/site/v2/sports/soccer/all/teams/"
                + team + "/schedule?fixture=true");
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
                    Match m = parse((JSONObject) read(r));
                    if (m != null && !"post".equals(m.state)) out.add(m);
                }
                break; // ya tenemos lo que queríamos: cortamos la descarga
            }
        } finally {
            http.disconnect();
        }
        return out;
    }

    private static Match parse(JSONObject e) {
        try {
            JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
            JSONArray comps = c.getJSONArray("competitors");
            Team home = null, away = null;
            for (int i = 0; i < comps.length(); i++) {
                JSONObject cp = comps.getJSONObject(i);
                JSONObject t = cp.getJSONObject("team");
                String id = t.optString("id");
                String name = SHORT.containsKey(id) ? SHORT.get(id) : t.optString("shortDisplayName", t.optString("displayName"));
                JSONArray logos = t.optJSONArray("logos");
                String logo = logos != null && logos.length() > 0 ? logos.getJSONObject(0).optString("href") : null;
                Object sc = cp.opt("score");
                String score = sc instanceof JSONObject ? ((JSONObject) sc).optString("displayValue") : null;
                Team team = new Team(id, name, logo, score);
                if ("home".equals(cp.optString("homeAway"))) home = team;
                else away = team;
            }
            if (home == null || away == null) return null;
            JSONObject st = c.optJSONObject("status");
            JSONObject type = st == null ? null : st.optJSONObject("type");
            String state = type == null ? "pre" : type.optString("state", "pre");
            String clock = st == null ? "" : st.optString("displayClock", "");
            JSONObject league = e.optJSONObject("league");
            String lg = league == null ? "" : league.optString("abbreviation", league.optString("name", ""));
            JSONArray links = e.optJSONArray("links");
            String link = links != null && links.length() > 0 ? links.getJSONObject(0).optString("href") : null;
            SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", Locale.US);
            iso.setTimeZone(TimeZone.getTimeZone("UTC"));
            long time = iso.parse(e.getString("date")).getTime();
            return new Match(e.getString("id"), time, prettyLeague(lg), link, state, clock, home, away);
        } catch (Exception ex) {
            return null;
        }
    }

    private static String prettyLeague(String lg) {
        if (lg.contains("Champions")) return "Champions";
        if (lg.contains("Friendly")) return "Amistoso";
        if (lg.contains("LigaPro")) return "LigaPro";
        if (lg.contains("Libertadores")) return "Libertadores";
        if (lg.contains("Sudamericana")) return "Sudamericana";
        if (lg.contains("World Cup Qualifying") || lg.contains("Eliminatorias")) return "Eliminatorias";
        if (lg.equalsIgnoreCase("LALIGA")) return "LaLiga";
        return lg;
    }

    /** Convierte el valor JSON actual del lector en JSONObject/JSONArray/String/Number/Boolean. */
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
            case NUMBER:
                return r.nextString();
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

    static List<String> favs(String csv) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String s : csv.split(",")) if (!s.isEmpty() && seen.add(s)) out.add(s);
        return out;
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(8000);
        http.setReadTimeout(8000);
        http.setRequestProperty("User-Agent", "InicioATV");
        return http;
    }
}
