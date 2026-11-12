package de.omatv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Winziger Webserver auf dem Fernseher. Darueber wird die App bequem vom Handy aus eingerichtet
 * (M3U-Link einfuegen, Sender auswaehlen und sortieren). Reines Java, ohne Android-Abhaengigkeiten.
 */
public final class SetupServer {

    /** Was die Einrichtungsseite vom Fernseher lesen und aendern darf. Aufrufe kommen aus Server-Threads. */
    public interface Backend {
        String getVersion();

        String getSources();

        void setSources(String text);

        String getStatus();

        boolean isLoading();

        /** Zaehler, der sich bei jeder Aenderung der Senderliste oder Auswahl erhoeht. */
        int getRevision();

        List<Channel> getAllChannels();

        List<String> getFavoriteKeys();

        void setFavoriteKeys(List<String> keys);

        String getCurrentKey();

        void reload();

        void play(String key);

        boolean isAutostartAllowed();

        void requestAutostart();
    }

    private static final int FIRST_PORT = 8080;
    private static final int LAST_PORT = 8089;
    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private final Backend backend;
    private final byte[] page;
    private volatile ServerSocket server;
    private volatile boolean running;
    private boolean closed;
    private ExecutorService pool;

    public SetupServer(Backend backend, String pageHtml) {
        this.backend = backend;
        this.page = pageHtml.getBytes(StandardCharsets.UTF_8);
    }

    /** Startet den Server und liefert den Port. */
    public synchronized int start() throws IOException {
        if (closed) throw new IOException("Server wurde beendet");
        if (running && server != null) return server.getLocalPort();
        IOException last = null;
        // Der gewohnte Port kann noch kurz vom vorherigen Start belegt sein: erst ein paar Mal versuchen,
        // damit die Adresse auf dem Handy gleich bleibt, und erst dann auf einen anderen Port ausweichen.
        for (int attempt = 0; attempt < 8 && server == null; attempt++) {
            try {
                server = bind(FIRST_PORT);
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        for (int port = FIRST_PORT + 1; port <= LAST_PORT && server == null; port++) {
            try {
                server = bind(port);
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last != null ? last : new IOException("Kein freier Port");
        running = true;
        pool = Executors.newFixedThreadPool(4);
        final ServerSocket s = server;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop(s);
            }
        }, "oma-tv-setup");
        t.setDaemon(true);
        t.start();
        return s.getLocalPort();
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket s = new ServerSocket();
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port), 16);
            return s;
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    public synchronized void stop() {
        closed = true;
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void acceptLoop(ServerSocket s) {
        while (running) {
            final Socket client;
            try {
                client = s.accept();
            } catch (IOException e) {
                break;
            }
            ExecutorService p = pool;
            if (p == null) {
                closeQuietly(client);
                break;
            }
            try {
                p.execute(new Runnable() {
                    @Override
                    public void run() {
                        handle(client);
                    }
                });
            } catch (RuntimeException e) {
                closeQuietly(client);
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // Kopfzeilen lesen
            ByteArrayOutputStream headBuf = new ByteArrayOutputStream(1024);
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b < 0) return;
                headBuf.write(b);
                if (headBuf.size() > MAX_HEADER) {
                    send(out, 431, "text/plain; charset=utf-8", bytes("Anfrage zu groß"));
                    return;
                }
                if (b == '\r' && (state == 0 || state == 2)) state++;
                else if (b == '\n' && (state == 1 || state == 3)) state++;
                else state = 0;
            }
            String head = headBuf.toString("ISO-8859-1");
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2) {
                send(out, 400, "text/plain; charset=utf-8", bytes("Ungültige Anfrage"));
                return;
            }
            String method = request[0].toUpperCase(Locale.ROOT);
            String path = request[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            int length = 0;
            boolean marker = false;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon <= 0) continue;
                String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = lines[i].substring(colon + 1).trim();
                if (name.equals("content-length")) {
                    try {
                        length = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        length = -1;
                    }
                } else if (name.equals("x-oma-tv")) {
                    marker = true;
                }
            }
            if (length < 0 || length > MAX_BODY) {
                send(out, 413, "text/plain; charset=utf-8", bytes("Zu viele Daten"));
                return;
            }
            byte[] bodyBytes = new byte[length];
            int got = 0;
            while (got < length) {
                int n = in.read(bodyBytes, got, length - got);
                if (n < 0) return;
                got += n;
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);

            if (method.equals("GET")) {
                if (path.equals("/") || path.equals("/index.html")) {
                    send(out, 200, "text/html; charset=utf-8", page);
                } else if (path.equals("/api/status")) {
                    send(out, 200, "application/json; charset=utf-8", bytes(statusJson()));
                } else if (path.equals("/api/state")) {
                    send(out, 200, "application/json; charset=utf-8", bytes(stateJson()));
                } else {
                    send(out, 404, "text/plain; charset=utf-8", bytes("Nicht gefunden"));
                }
                return;
            }
            if (method.equals("POST")) {
                // Der eigene Kopf verhindert, dass fremde Webseiten heimlich Einstellungen aendern.
                if (!marker) {
                    send(out, 403, "text/plain; charset=utf-8", bytes("Nicht erlaubt"));
                    return;
                }
                if (path.equals("/api/sources")) {
                    backend.setSources(body.replace("\r", "").trim());
                } else if (path.equals("/api/favorites")) {
                    List<String> keys = new ArrayList<String>();
                    String[] parts = body.replace("\r", "").split("\n");
                    for (int i = 0; i < parts.length; i++) {
                        String k = parts[i].trim();
                        if (!k.isEmpty() && !keys.contains(k)) keys.add(k);
                    }
                    backend.setFavoriteKeys(keys);
                } else if (path.equals("/api/reload")) {
                    backend.reload();
                } else if (path.equals("/api/play")) {
                    backend.play(body.replace("\r", "").replace("\n", "").trim());
                } else if (path.equals("/api/autostart")) {
                    backend.requestAutostart();
                } else {
                    send(out, 404, "text/plain; charset=utf-8", bytes("Nicht gefunden"));
                    return;
                }
                send(out, 200, "application/json; charset=utf-8", bytes("{\"ok\":true}"));
                return;
            }
            send(out, 405, "text/plain; charset=utf-8", bytes("Nicht erlaubt"));
        } catch (Exception ignored) {
            // Verbindungsabbrueche vom Handy sind normal und kein Fehler.
        } finally {
            closeQuietly(client);
        }
    }

    private String statusJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendStatusFields(sb);
        sb.append('}');
        return sb.toString();
    }

    private void appendStatusFields(StringBuilder sb) {
        sb.append("\"version\":").append(quote(backend.getVersion()));
        sb.append(",\"status\":").append(quote(backend.getStatus()));
        sb.append(",\"loading\":").append(backend.isLoading());
        sb.append(",\"rev\":").append(backend.getRevision());
        sb.append(",\"current\":").append(quote(backend.getCurrentKey()));
        sb.append(",\"autostart\":").append(backend.isAutostartAllowed());
        sb.append(",\"count\":").append(backend.getAllChannels().size());
    }

    private String stateJson() {
        int rev = backend.getRevision();
        List<Channel> all = backend.getAllChannels();
        List<String> favorites = backend.getFavoriteKeys();
        StringBuilder sb = new StringBuilder(all.size() * 64 + 512);
        sb.append('{');
        appendStatusFields(sb);
        // Die Revision gehoert zu genau diesem Schnappschuss der Liste
        sb.append(",\"listRev\":").append(rev);
        sb.append(",\"sources\":").append(quote(backend.getSources()));
        sb.append(",\"favorites\":[");
        for (int i = 0; i < favorites.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(quote(favorites.get(i)));
        }
        sb.append("],\"channels\":[");
        for (int i = 0; i < all.size(); i++) {
            Channel c = all.get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"k\":").append(quote(c.key));
            sb.append(",\"n\":").append(quote(c.name));
            sb.append(",\"g\":").append(quote(c.group)).append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** JSON-Zeichenkette mit allen noetigen Maskierungen. */
    static String quote(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        String hex = Integer.toHexString(c);
                        sb.append("\\u");
                        for (int k = hex.length(); k < 4; k++) sb.append('0');
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found" : code == 403 ? "Forbidden" : "Error";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
