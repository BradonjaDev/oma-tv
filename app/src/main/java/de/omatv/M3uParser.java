package de.omatv;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Liest eine M3U-Senderliste. Reines Java, ohne Android-Abhaengigkeiten. */
public final class M3uParser {

    public static final class Result {
        public final List<Channel> channels = new ArrayList<Channel>();
        /** true, wenn der Link kein Sender-Verzeichnis ist, sondern selbst ein einzelner HLS-Stream. */
        public boolean singleStream;
        /** Anzahl uebersprungener Filme/Serien. */
        public int skippedVod;
        /** Anzahl uebersprungener Eintraege mit nicht unterstuetzter Adresse (z. B. udp://). */
        public int skippedOther;
    }

    private M3uParser() {}

    public static Result parse(BufferedReader in) throws IOException {
        Result r = new Result();
        String name = null;
        String group = null;
        String extGrp = null;
        String ua = null;
        String ref = null;
        boolean first = true;
        String line;
        while ((line = in.readLine()) != null) {
            if (first) {
                first = false;
                if (line.length() > 0 && line.charAt(0) == '﻿') line = line.substring(1);
            }
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.charAt(0) == '#') {
                if (startsWith(line, "#EXTINF:")) {
                    String[] parsed = parseExtInf(line.substring(8));
                    name = parsed[0];
                    group = parsed[1];
                    ua = null;
                    ref = null;
                } else if (startsWith(line, "#EXTGRP:")) {
                    extGrp = line.substring(8).trim();
                } else if (startsWith(line, "#EXTVLCOPT:")) {
                    String opt = line.substring(11).trim();
                    if (startsWith(opt, "http-user-agent=")) ua = opt.substring(16).trim();
                    else if (startsWith(opt, "http-referrer=")) ref = opt.substring(14).trim();
                    else if (startsWith(opt, "http-referer=")) ref = opt.substring(13).trim();
                } else if (startsWith(line, "#EXT-X-TARGETDURATION")
                        || startsWith(line, "#EXT-X-STREAM-INF")
                        || startsWith(line, "#EXT-X-MEDIA-SEQUENCE")) {
                    r.singleStream = true;
                }
                continue;
            }

            // Adresszeile
            String url = line;
            int bar = url.indexOf('|');
            if (bar > 0) {
                String opts = url.substring(bar + 1);
                url = url.substring(0, bar).trim();
                String[] parts = opts.split("&");
                for (int i = 0; i < parts.length; i++) {
                    int eq = parts[i].indexOf('=');
                    if (eq <= 0) continue;
                    String k = parts[i].substring(0, eq).trim();
                    String v = percentDecode(parts[i].substring(eq + 1).trim());
                    if (k.equalsIgnoreCase("user-agent")) ua = v;
                    else if (k.equalsIgnoreCase("referer") || k.equalsIgnoreCase("referrer")) ref = v;
                }
            }

            String lower = url.toLowerCase(Locale.ROOT);
            if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
                r.skippedOther++;
            } else if (isVod(lower)) {
                r.skippedVod++;
            } else {
                String g = (group != null && !group.isEmpty()) ? group : (extGrp != null ? extGrp : "");
                String n = (name != null && !name.isEmpty()) ? name : nameFromUrl(url, r.channels.size() + 1);
                r.channels.add(new Channel(n, url, g, ua, ref));
            }
            name = null;
            group = null;
            extGrp = null;
            ua = null;
            ref = null;
        }
        return r;
    }

    /** Liefert {Name, Gruppe} aus dem Text hinter "#EXTINF:". */
    static String[] parseExtInf(String s) {
        int comma = -1;
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            else if (c == ',' && !inQuotes) {
                comma = i;
                break;
            }
        }
        if (comma < 0) comma = s.lastIndexOf(',');
        String attrs = comma >= 0 ? s.substring(0, comma) : s;
        String title = comma >= 0 ? s.substring(comma + 1).trim() : "";
        if (title.isEmpty()) title = attr(attrs, "tvg-name");
        return new String[] {title, attr(attrs, "group-title")};
    }

    /** Liest den Wert von key="..." (Gross-/Kleinschreibung egal). */
    static String attr(String attrs, String key) {
        String lower = attrs.toLowerCase(Locale.ROOT);
        String needle = key + "=\"";
        int from = 0;
        while (true) {
            int i = lower.indexOf(needle, from);
            if (i < 0) return "";
            // Treffer muss am Wortanfang stehen (sonst passt z. B. "xgroup-title")
            if (i == 0 || Character.isWhitespace(lower.charAt(i - 1))) {
                int start = i + needle.length();
                int end = attrs.indexOf('"', start);
                if (end < 0) end = attrs.length();
                return attrs.substring(start, end).trim();
            }
            from = i + 1;
        }
    }

    static boolean isVod(String lowerUrl) {
        String path = lowerUrl;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.contains("/movie/") || path.contains("/series/")) return true;
        return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi")
                || path.endsWith(".mov") || path.endsWith(".wmv") || path.endsWith(".flv")
                || path.endsWith(".m4v") || path.endsWith(".mpg") || path.endsWith(".mpeg");
    }

    static String nameFromUrl(String url, int number) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = last.lastIndexOf('.');
        if (dot > 0) last = last.substring(0, dot);
        last = last.trim();
        // Reine Zahlen oder sehr kurze Reste sind als Name wenig hilfreich
        boolean useful = last.length() >= 3 && !last.matches("[0-9]+") && path.indexOf("://") < slash - 2;
        return useful ? last : "Sender " + number;
    }

    private static boolean startsWith(String s, String prefix) {
        return s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
