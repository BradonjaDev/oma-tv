package de.omatv;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.Locale;

/**
 * Prueft kurz, ob ein Sender gerade erreichbar ist: Verbindung aufbauen, Antwort ansehen,
 * ein Byte lesen, wieder schliessen. Reines Java, ohne Android-Abhaengigkeiten.
 */
public final class StreamChecker {

    private StreamChecker() {}

    public static boolean isReachable(Channel channel, String defaultUserAgent) {
        String current = channel.url;
        try {
            for (int hop = 0; hop < 5; hop++) {
                URL url = new URL(current);
                URLConnection con = url.openConnection();
                if (!(con instanceof HttpURLConnection)) return false;
                HttpURLConnection http = (HttpURLConnection) con;
                try {
                    http.setConnectTimeout(5000);
                    http.setReadTimeout(6000);
                    http.setInstanceFollowRedirects(false);
                    http.setRequestProperty("User-Agent",
                            channel.userAgent != null ? channel.userAgent : defaultUserAgent);
                    http.setRequestProperty("Accept", "*/*");
                    if (channel.referrer != null) http.setRequestProperty("Referer", channel.referrer);
                    int code = http.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String location = http.getHeaderField("Location");
                        if (location == null || location.isEmpty()) return false;
                        current = new URL(url, location).toString();
                        continue;
                    }
                    if (code != 200 && code != 206) return false;
                    String type = http.getContentType();
                    // Eine Webseite statt eines Senders ist meist eine Fehler- oder Sperrseite.
                    if (type != null && type.toLowerCase(Locale.ROOT).startsWith("text/html")) return false;
                    InputStream in = http.getInputStream();
                    try {
                        return in.read() >= 0;
                    } finally {
                        in.close();
                    }
                } finally {
                    http.disconnect();
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
