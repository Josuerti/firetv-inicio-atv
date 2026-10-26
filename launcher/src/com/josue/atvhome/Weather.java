package com.josue.atvhome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.TimeZone;

/**
 * Clima actual y próximas horas de la ciudad principal de la zona horaria, vía Open-Meteo
 * (gratis, sin cuenta ni ubicación). Idea tomada de Luma TV (MIT, github.com/0xProbabillity/luma-tv).
 */
final class Weather {
    static final int HOURS = 5;

    final String city;
    final int temp, max, min, code;
    final boolean day;
    final String[] hourLabel = new String[HOURS];
    final int[] hourTemp = new int[HOURS];
    final int[] hourCode = new int[HOURS];
    final boolean[] hourDay = new boolean[HOURS];

    private Weather(String city, int temp, int max, int min, int code, boolean day) {
        this.city = city;
        this.temp = temp;
        this.max = max;
        this.min = min;
        this.code = code;
        this.day = day;
    }

    /** {ciudad, latitud, longitud} para las zonas horarias más probables; si no, Milagro (Ecuador). */
    private static String[] cityFor(String tz) {
        switch (tz) {
            case "America/Bogota": return new String[]{"Bogotá", "4.71", "-74.07"};
            case "America/Lima": return new String[]{"Lima", "-12.05", "-77.04"};
            case "America/Mexico_City": return new String[]{"Ciudad de México", "19.43", "-99.13"};
            case "America/Santiago": return new String[]{"Santiago", "-33.45", "-70.67"};
            case "America/Argentina/Buenos_Aires": return new String[]{"Buenos Aires", "-34.60", "-58.38"};
            case "America/New_York": return new String[]{"Nueva York", "40.71", "-74.01"};
            case "Europe/Madrid": return new String[]{"Madrid", "40.42", "-3.70"};
            // America/Guayaquil y resto: Milagro, Guayas (geocoding de Open-Meteo)
            default: return new String[]{"Milagro", "-2.13396", "-79.59337"};
        }
    }

    static Weather fetch() throws Exception {
        String[] c = cityFor(TimeZone.getDefault().getID());
        URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + c[1] + "&longitude=" + c[2]
                + "&current=temperature_2m,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,is_day&forecast_hours=" + (HOURS + 1)
                + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto");
        HttpURLConnection http = (HttpURLConnection) url.openConnection();
        http.setConnectTimeout(8000);
        http.setReadTimeout(8000);
        try (InputStream in = http.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            JSONObject j = new JSONObject(out.toString("UTF-8"));
            JSONObject cur = j.getJSONObject("current");
            JSONObject daily = j.getJSONObject("daily");
            Weather w = new Weather(c[0],
                    (int) Math.round(cur.getDouble("temperature_2m")),
                    (int) Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)),
                    (int) Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)),
                    cur.getInt("weather_code"),
                    cur.optInt("is_day", 1) == 1);
            JSONObject h = j.getJSONObject("hourly");
            JSONArray times = h.getJSONArray("time");
            JSONArray temps = h.getJSONArray("temperature_2m");
            JSONArray codes = h.getJSONArray("weather_code");
            JSONArray days = h.optJSONArray("is_day");
            for (int i = 0; i < HOURS && i < times.length(); i++) {
                String t = times.getString(i); // 2026-10-26T14:00
                w.hourLabel[i] = i == 0 ? "Ahora" : t.substring(t.indexOf('T') + 1, t.indexOf('T') + 3);
                w.hourTemp[i] = (int) Math.round(temps.getDouble(i));
                w.hourCode[i] = codes.getInt(i);
                w.hourDay[i] = days == null || days.optInt(i, 1) == 1;
            }
            return w;
        } finally {
            http.disconnect();
        }
    }

    /** Códigos WMO resumidos. */
    static String emoji(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code <= 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 95) return "⛈️";
        if (code >= 80) return "🌦️";
        if (code >= 71 && code <= 77) return "❄️";
        return "🌧️";
    }

    static String describe(int code) {
        if (code == 0) return "Despejado";
        if (code <= 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Niebla";
        if (code >= 95) return "Tormenta";
        if (code >= 80) return "Chubascos";
        if (code >= 71 && code <= 77) return "Nieve";
        if (code >= 61) return "Lluvia";
        return "Llovizna";
    }
}
