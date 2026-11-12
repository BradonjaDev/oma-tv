package de.omatv;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Laedt die hinterlegten M3U-Links und macht daraus eine Senderliste.
 * Reines Java, ohne Android-Abhaengigkeiten.
 */
public final class PlaylistLoader {

    /** Obergrenze fuer eine Senderliste (schuetzt vor Endlos-Downloads). */
    private static final long MAX_BYTES = 80L * 1024 * 1024;

    public static final class Source {
        public final String name;
        public final String url;

        Source(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    public static final class Outcome {
        public final List<Channel> channels = new ArrayList<Channel>();
        public final List<String> errors = new ArrayList<String>();
        public int skippedVod;
    }

    private PlaylistLoader() {}

    /** Zerlegt das Eingabefeld: ein Link pro Zeile, optional mit Namen davor ("ZDF https://..."). */
    public static List<Source> parseSources(String text) {
        List<Source> out = new ArrayList<Source>();
        if (text == null) return out;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            int at = lower.indexOf("http://");
            int at2 = lower.indexOf("https://");
            if (at < 0 || (at2 >= 0 && at2 < at)) at = at2;
            if (at < 0) {
                // Adresse ohne http:// davor eingetippt
                if (line.indexOf(' ') < 0 && line.indexOf('.') > 0) out.add(new Source("", "http://" + line));
                continue;
            }
            String name = line.substring(0, at).trim();
            while (name.length() > 0 && ",;=|:-".indexOf(name.charAt(name.length() - 1)) >= 0) {
                name = name.substring(0, name.length() - 1).trim();
            }
            String url = line.substring(at).trim();
            int space = url.indexOf(' ');
            if (space > 0) url = url.substring(0, space);
            out.add(new Source(name, url));
        }
        return out;
    }

    /** Laedt alle Links. Wirft nie; Fehler stehen in {@link Outcome#errors}. */
    public static Outcome loadAll(String sourcesText, String userAgent) {
        Outcome out = new Outcome();
        List<Source> sources = parseSources(sourcesText);
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            try {
                loadOne(s, userAgent, out);
            } catch (Exception e) {
                out.errors.add(shortUrl(s.url) + ": " + describe(e));
            }
        }
        assignKeys(out.channels);
        return out;
    }

    private static void loadOne(Source s, String userAgent, Outcome out) throws IOException {
        URLConnection con = open(s.url, userAgent);
        InputStream raw = null;
        try {
            String type = con.getContentType();
            type = type == null ? "" : type.toLowerCase(Locale.ROOT);
            if ((type.startsWith("video/") || type.startsWith("audio/")) && type.indexOf("mpegurl") < 0) {
                // Der Link ist direkt ein Sender (z. B. ein .ts-Stream)
                out.channels.add(single(s, out.channels.size() + 1));
                return;
            }
            raw = con.getInputStream();
            BufferedInputStream in = new BufferedInputStream(new Limited(raw, MAX_BYTES), 16384);
            in.mark(1024);
            byte[] head = new byte[512];
            int n = readSome(in, head);
            in.reset();
            int kind = sniff(head, n);
            if (kind == SNIFF_BINARY) {
                out.channels.add(single(s, out.channels.size() + 1));
                return;
            }
            if (kind == SNIFF_HTML) {
                throw new IOException("Der Link liefert eine Webseite statt einer Senderliste");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 16384);
            M3uParser.Result r = M3uParser.parse(reader);
            if (r.singleStream) {
                out.channels.add(single(s, out.channels.size() + 1));
                return;
            }
            out.skippedVod += r.skippedVod;
            if (r.channels.isEmpty()) {
                throw new IOException(r.skippedVod > 0
                        ? "Die Liste enthält nur Filme/Serien, keine Sender"
                        : "In der Liste wurden keine Sender gefunden");
            }
            out.channels.addAll(r.channels);
        } finally {
            if (raw != null) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                }
            }
            if (con instanceof HttpURLConnection) ((HttpURLConnection) con).disconnect();
        }
    }

    private static Channel single(Source s, int number) {
        String name = s.name.isEmpty() ? M3uParser.nameFromUrl(s.url, number) : s.name;
        return new Channel(name, s.url, "", null, null);
    }

    /** Oeffnet die Verbindung und folgt Weiterleitungen, auch von http nach https. */
    private static URLConnection open(String address, String userAgent) throws IOException {
        String current = address;
        for (int hop = 0; hop < 6; hop++) {
            URL url = new URL(current);
            URLConnection con = url.openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            if (!(con instanceof HttpURLConnection)) return con;
            HttpURLConnection http = (HttpURLConnection) con;
            http.setInstanceFollowRedirects(false);
            http.setRequestProperty("User-Agent", userAgent);
            http.setRequestProperty("Accept", "*/*");
            int code = http.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = http.getHeaderField("Location");
                http.disconnect();
                if (location == null || location.isEmpty()) throw new IOException("Weiterleitung ohne Ziel");
                current = new URL(url, location).toString();
                continue;
            }
            if (code != 200) {
                http.disconnect();
                throw new IOException("Server antwortet mit Fehler " + code
                        + (code == 401 || code == 403 ? " (Zugangsdaten im Link prüfen)" : "")
                        + (code == 404 ? " (Link nicht gefunden)" : ""));
            }
            return http;
        }
        throw new IOException("Zu viele Weiterleitungen");
    }

    private static final int SNIFF_TEXT = 0;
    private static final int SNIFF_BINARY = 1;
    private static final int SNIFF_HTML = 2;

    static int sniff(byte[] head, int n) {
        if (n <= 0) return SNIFF_TEXT;
        int i = 0;
        if (n >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) i = 3;
        while (i < n && (head[i] == ' ' || head[i] == '\r' || head[i] == '\n' || head[i] == '\t')) i++;
        if (i < n && head[i] == '<') return SNIFF_HTML;
        int control = 0;
        for (int k = 0; k < n; k++) {
            int b = head[k] & 0xFF;
            if (b < 9 || (b > 13 && b < 32)) control++;
        }
        return control > n / 20 ? SNIFF_BINARY : SNIFF_TEXT;
    }

    private static int readSome(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    /** Vergibt eindeutige Schluessel: der Name, bei gleichen Namen "Name #2", "Name #3" ... */
    public static void assignKeys(List<Channel> channels) {
        Map<String, Integer> seen = new HashMap<String, Integer>();
        for (int i = 0; i < channels.size(); i++) {
            Channel c = channels.get(i);
            Integer count = seen.get(c.name);
            int n = count == null ? 1 : count.intValue() + 1;
            seen.put(c.name, Integer.valueOf(n));
            c.key = n == 1 ? c.name : c.name + " #" + n;
        }
    }

    // ---- Zwischenspeicher, damit der Fernseher auch ohne erreichbare Liste sofort startet ----

    public static void writeCache(File file, List<Channel> channels) throws IOException {
        File tmp = new File(file.getPath() + ".tmp");
        Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
        try {
            for (int i = 0; i < channels.size(); i++) {
                Channel c = channels.get(i);
                w.write(clean(c.name));
                w.write('\t');
                w.write(clean(c.group));
                w.write('\t');
                w.write(clean(c.url));
                w.write('\t');
                w.write(clean(c.userAgent));
                w.write('\t');
                w.write(clean(c.referrer));
                w.write('\n');
            }
        } finally {
            w.close();
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            if (!tmp.renameTo(file)) throw new IOException("Zwischenspeicher konnte nicht geschrieben werden");
        }
    }

    public static List<Channel> readCache(File file) throws IOException {
        List<Channel> out = new ArrayList<Channel>();
        if (!file.exists()) return out;
        BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 16384);
        try {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.split("\t", -1);
                if (f.length < 5 || f[2].isEmpty()) continue;
                out.add(new Channel(f[0], f[2], f[1], f[3], f[4]));
            }
        } finally {
            r.close();
        }
        assignKeys(out);
        return out;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    static String shortUrl(String url) {
        try {
            String host = new URL(url).getHost();
            return host == null || host.isEmpty() ? "Link" : host;
        } catch (Exception e) {
            return "Link";
        }
    }

    static String describe(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "Adresse nicht gefunden (Tippfehler oder kein Internet?)";
        if (e instanceof java.net.SocketTimeoutException) return "Server antwortet nicht (Zeitüberschreitung)";
        if (e instanceof java.net.ConnectException) return "Verbindung abgelehnt";
        if (e instanceof java.net.MalformedURLException) return "Das ist keine gültige Adresse";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    /** Bricht ab, wenn mehr als die erlaubte Menge gelesen wird. */
    private static final class Limited extends FilterInputStream {
        private long left;

        Limited(InputStream in, long max) {
            super(in);
            left = max;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) throw new IOException("Die Senderliste ist zu groß");
            int b = super.read();
            if (b >= 0) left--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) throw new IOException("Die Senderliste ist zu groß");
            int n = super.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }
}
