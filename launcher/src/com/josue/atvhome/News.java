package com.josue.atvhome;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Titulares de El Universo (RSS público). Solo texto: se leen las primeras noticias del
 * feed en streaming y se corta la descarga, sin imágenes ni librerías.
 */
final class News {
    static final String SOURCE = "El Universo";
    private static final String FEED = "https://www.eluniverso.com/arc/outboundfeeds/rss/?outputType=xml";
    private static final int SCAN = 15;

    static final class Item {
        final String title, link;
        final long time;

        Item(String title, String link, long time) {
            this.title = title;
            this.link = link;
            this.time = time;
        }
    }

    static List<Item> fetch(int max) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(FEED).openConnection();
        http.setConnectTimeout(8000);
        http.setReadTimeout(8000);
        http.setRequestProperty("User-Agent", "InicioATV");
        List<Item> items = new ArrayList<>();
        SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        try (InputStream in = http.getInputStream()) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            String title = null, link = null, date = null, tag = null;
            boolean inItem = false;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && items.size() < SCAN; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    if ("item".equals(tag)) {
                        inItem = true;
                        title = link = date = null;
                    }
                } else if (ev == XmlPullParser.TEXT && inItem && tag != null) {
                    String t = p.getText().trim();
                    if (t.isEmpty()) continue;
                    if ("title".equals(tag)) title = t;
                    else if ("link".equals(tag)) link = t;
                    else if ("pubDate".equals(tag)) date = t;
                } else if (ev == XmlPullParser.END_TAG) {
                    if ("item".equals(p.getName()) && title != null) {
                        long time = 0;
                        try {
                            time = rfc.parse(date).getTime();
                        } catch (Exception ignored) {
                        }
                        items.add(new Item(title, link, time));
                        inItem = false;
                    }
                    tag = null;
                }
            }
        } finally {
            http.disconnect(); // cortamos aquí: no hace falta bajar el resto del feed
        }
        Collections.sort(items, (a, b) -> Long.compare(b.time, a.time));
        return items.size() > max ? new ArrayList<>(items.subList(0, max)) : items;
    }
}
