package com.josue.atvhome;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Titulares con foto de El Universo (RSS público). Se leen las primeras noticias del feed en
 * streaming (cortando la descarga) y cada foto se decodifica ya reducida al tamaño del recuadro.
 */
final class News {
    static final String SOURCE = "El Universo";
    private static final String FEED = "https://www.eluniverso.com/arc/outboundfeeds/rss/?outputType=xml";
    private static final int SCAN = 15;

    static final class Item {
        final String title, link, image;
        final long time;
        Bitmap photo;

        Item(String title, String link, String image, long time) {
            this.title = title;
            this.link = link;
            this.image = image;
            this.time = time;
        }
    }

    /** Las {@code max} noticias más recientes, con su foto ya reducida a unos {@code photoW} px de ancho. */
    static List<Item> fetch(int max, int photoW) throws Exception {
        HttpURLConnection http = open(FEED);
        List<Item> items = new ArrayList<>();
        SimpleDateFormat rfc = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        try (InputStream in = http.getInputStream()) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(in, "UTF-8");
            String title = null, link = null, date = null, image = null, tag = null;
            boolean inItem = false;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && items.size() < SCAN; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG) {
                    tag = p.getName();
                    if ("item".equals(tag)) {
                        inItem = true;
                        title = link = date = image = null;
                    } else if (inItem && image == null && tag.endsWith("content")
                            && p.getAttributeValue(null, "url") != null) {
                        image = p.getAttributeValue(null, "url"); // <media:content url="…">
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
                        items.add(new Item(title, link, image, time));
                        inItem = false;
                    }
                    tag = null;
                }
            }
        } finally {
            http.disconnect(); // cortamos aquí: no hace falta bajar el resto del feed
        }
        // Preferimos noticias con foto y, entre ellas, las más recientes.
        Collections.sort(items, (a, b) -> {
            if ((a.image == null) != (b.image == null)) return a.image == null ? 1 : -1;
            return Long.compare(b.time, a.time);
        });
        List<Item> out = new ArrayList<>(items.subList(0, Math.min(max, items.size())));
        for (Item it : out) {
            if (it.image == null) continue;
            try {
                it.photo = photo(it.image, photoW);
            } catch (Exception ignored) {
                // sin foto: el recuadro muestra solo el titular
            }
        }
        return out;
    }

    private static Bitmap photo(String url, int targetW) throws Exception {
        HttpURLConnection http = open(url);
        try (InputStream in = http.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            for (int n; (n = in.read(b)) > 0; ) buf.write(b, 0, n);
            byte[] data = buf.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565; // fotos sin transparencia: la mitad de memoria
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } finally {
            http.disconnect();
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
