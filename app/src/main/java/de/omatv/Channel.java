package de.omatv;

/** Ein Fernsehsender aus der M3U-Liste. */
public final class Channel {
    public final String name;
    public final String url;
    public final String group;
    /** Optionaler User-Agent aus der Liste (kann null sein). */
    public final String userAgent;
    /** Optionaler Referer aus der Liste (kann null sein). */
    public final String referrer;
    /** Eindeutiger Schluessel, wird nach dem Laden vergeben (Name, bei Dubletten "Name #2"). */
    public String key = "";

    public Channel(String name, String url, String group, String userAgent, String referrer) {
        this.name = name == null ? "" : name;
        this.url = url == null ? "" : url;
        this.group = group == null ? "" : group;
        this.userAgent = (userAgent == null || userAgent.isEmpty()) ? null : userAgent;
        this.referrer = (referrer == null || referrer.isEmpty()) ? null : referrer;
    }
}
