<div align="center">

<img src="app/src/main/res/drawable-xhdpi/banner.png" alt="Oma TV" width="320">

# Oma TV

**Fernsehen wie früher.** Ein IPTV-Player für Android TV mit genau einer Aufgabe:
Omas Sender zeigen und mit ▲▼ umschalten.

[![APK bauen](https://github.com/BradonjaDev/oma-tv/actions/workflows/build.yml/badge.svg)](https://github.com/BradonjaDev/oma-tv/actions/workflows/build.yml)
[![Download](https://img.shields.io/badge/Download-oma--tv.apk-ffc107?logo=android&logoColor=white)](https://github.com/BradonjaDev/oma-tv/releases/latest/download/oma-tv.apk)
![Android](https://img.shields.io/badge/Android-7.0%2B-3ddc84?logo=android&logoColor=white)

</div>

---

## Warum?

Moderne TV-Apps haben Menüs, Kacheln, Werbung und Abos. Oma will das Erste, das ZDF und
den Dritten. Oma TV startet direkt im zuletzt gesehenen Sender und lässt sich mit drei
Tasten bedienen. Mehr gibt es nicht, und genau das ist der Punkt.

## Was die App kann

- **Sofort Fernsehen:** startet mit dem zuletzt gesehenen Sender, auf Wunsch von selbst nach dem Einschalten
- **Bedienung wie am alten Fernseher:** ▲▼ oder P+/P− schaltet um, Zahlen wählen direkt, OK zeigt die Senderliste
- **Oma-sicher:** „Zurück“ verlässt die App am Fernseher nicht, es gibt nichts zu verstellen
- **Fällt ein Sender aus,** versucht die App es von selbst weiter
- **Einrichtung vom Handy:** die App zeigt eine Adresse an, im Browser fügst du den M3U-Link ein und wählst Omas Sender aus
- **Läuft auch auf Handy und Tablet:** mit Wischen und Tippen, im Hoch- und Querformat
- Kein Konto, keine Werbung, keine Datensammlung

## Installieren

Die aktuelle Version liegt immer hier:

```
https://github.com/BradonjaDev/oma-tv/releases/latest/download/oma-tv.apk
```

Auf dem TV-Stick am einfachsten mit der App **Downloader**: Adresse eintippen, installieren, fertig.
Die ausführliche Schritt-für-Schritt-Anleitung steht in [ANLEITUNG.md](ANLEITUNG.md).

## Bedienung

| Fernbedienung | Handy / Tablet | Was passiert |
|---|---|---|
| ▲ / ▼ oder P+ / P− | nach oben / unten wischen, oder oben / unten tippen | nächster / vorheriger Sender |
| OK | in die Mitte tippen | Senderliste |
| Zahlen | – | Sender direkt wählen |
| Zurück | Zurück | TV: schließt nur die Liste · Handy: App in den Hintergrund |
| – | lange drücken | Einstellungen (M3U-Link, Sender auswählen) |

## Selbst bauen

Es ist kein Android Studio nötig. GitHub baut die APK bei jedem Push auf `main` und legt sie
im Release [`apk`](https://github.com/BradonjaDev/oma-tv/releases/tag/apk) ab.

Lokal geht es mit JDK 17 und dem Android SDK so:

```
./gradlew assembleRelease
```

## Aufbau

| Datei | Aufgabe |
|---|---|
| `MainActivity.java` | Player, Senderliste, Tasten und Touch, gesamte Oberfläche |
| `M3uParser.java` | liest M3U-Senderlisten |
| `PlaylistLoader.java` | lädt die Links, Zwischenspeicher für den Start ohne Netz |
| `SetupServer.java` | kleiner Webserver (Port 8080) für die Einrichtung |
| `assets/setup.html` | die Einrichtungsseite fürs Handy |
| `BootReceiver.java` | Autostart nach dem Hochfahren |

Java ohne Kotlin und ohne AppCompat, Media3/ExoPlayer für die Wiedergabe, ab Android 7.0 (API 24).

## Gut zu wissen

- Oma TV bringt **keine Sender mit**. Du brauchst einen eigenen M3U-Link, zum Beispiel von deinem Internet- oder TV-Anbieter.
- Die Einrichtungsseite kann jeder im selben WLAN öffnen, solange die App läuft.
- Der Signaturschlüssel liegt bewusst im Repository, damit sich Updates über die installierte App installieren lassen. Für eine private App ist das unkritisch.
