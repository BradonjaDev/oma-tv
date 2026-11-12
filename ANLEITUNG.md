# Oma TV – Anleitung

Eine Fernseh-App mit genau einer Aufgabe: Omas Sender zeigen und mit ▲▼ umschalten. Keine Werbung, kein Abo, keine Menüs. Eingerichtet wird alles bequem vom Handy aus.

**Du brauchst:** einen Google-TV-Stick am Fernseher, ein kostenloses GitHub-Konto und etwa 20 Minuten.

---

## 1. Die App holen

Die fertige Installationsdatei (APK) liegt immer unter dieser Adresse:

`github.com/BradonjaDev/oma-tv/releases/latest/download/oma-tv.apk`

GitHub baut sie bei jeder Änderung am Projekt automatisch neu. Du musst nichts programmieren.

**Eigene Kopie (optional):** Wer das Projekt selbst verwalten möchte, meldet sich auf [github.com](https://github.com) an, klickt auf der Projektseite oben rechts auf **Fork** und schaltet im Reiter **Actions** die Workflows ein. Nach dem ersten Lauf von „APK bauen“ liegt die APK unter `github.com/DEIN-NAME/oma-tv/releases/latest/download/oma-tv.apk`.

## 2. Auf dem Stick installieren

1. Stick normal einrichten (WLAN, Google-Konto).
2. Im Play Store auf dem Stick die App **Downloader** (von AFTVnews) installieren und öffnen.
3. Die Adresse aus Schritt 1 eintippen und **Go** drücken.
4. Der Stick fragt einmalig, ob Downloader Apps installieren darf: erlauben, dann **Installieren**. Eine Warnung vor „unbekannter App“ mit „Trotzdem installieren“ bestätigen.
5. **Oma TV** öffnen.

Falls du Downloader nicht findest: die APK am Handy über dieselbe Adresse herunterladen und mit der App „Send Files to TV“ (auf Handy und Stick installieren) übertragen.

## 3. Sender einrichten – vom Handy

Beim ersten Start zeigt der Fernseher eine Adresse wie `http://192.168.178.50:8080`.

1. Handy ins gleiche WLAN bringen und die Adresse im Browser öffnen.
2. **M3U-Link** einfügen → **Speichern und Sender laden**. Mehrere Links: einer pro Zeile. Statt eines Links kannst du auch den ganzen Inhalt einer M3U-Playlist einfügen.
   Noch keinen Link? Frei zugängliche deutsche Sender gibt es zum Beispiel hier: `https://iptv-org.github.io/iptv/countries/de.m3u` (Projekt [iptv-org/iptv](https://github.com/iptv-org/iptv)). Weitere gepflegte Listen stehen in der [README](README.md#wo-bekomme-ich-eine-senderliste).
3. Unter **Sender hinzufügen** bei Omas Sendern auf **+** tippen. Mit ▲▼ in ihre gewohnte Reihenfolge bringen (1 = Das Erste, 2 = ZDF …).
4. Auf einen Sendernamen tippen spielt ihn sofort am Fernseher – so testest du jeden Sender.

Später etwas ändern? Am Fernseher **OK** drücken: unten in der Senderliste steht die Adresse wieder.

## 4. Oma-sicher machen

- **App nach vorn:** Auf der Startseite des Sticks Oma TV markieren, OK gedrückt halten → **Verschieben** → an die erste Stelle.
- **HDMI-CEC einschalten** (im Menü des Fernsehers; heißt bei Samsung „Anynet+“, bei LG „Simplink“, bei Sony „Bravia Sync“, bei Philips „EasyLink“). Dann geht der Stick mit dem Fernseher an und aus, und Omas gewohnte Fernbedienung steuert meist auch die App.
- **Autostart (optional):** Auf der Handy-Seite ganz unten **Auf dem Fernseher erlauben** antippen, am Fernseher den Schalter bei Oma TV einschalten, Zurück drücken. Öffnet sich nichts: Einstellungen → Apps → Spezieller App-Zugriff → Über anderen Apps einblenden. Das klappt nicht auf jedem Gerät, ist aber meist unnötig: Der Stick schläft nur, und Oma TV bleibt vorne.

## 5. Bedienung für Oma

| Taste | Was passiert |
|---|---|
| ▲ / ▼ oder P+ / P− | nächster / vorheriger Sender |
| OK | Senderliste; mit ▲▼ aussuchen, OK drücken |
| Zahlen | Sender direkt wählen |
| Zurück | schließt die Liste – verlässt die App nicht |
| Lautstärke | wie gewohnt |

Fällt ein Sender aus, versucht die App es von selbst immer wieder. Oma muss nichts wegdrücken.

## 6. Am Handy oder Tablet

Oma TV läuft auch direkt auf einem Android-Handy oder -Tablet, im Hoch- und Querformat.

- **Einrichten:** Beim ersten Start auf **M3U-Link oder Playlist eingeben** tippen, den Link oder den Inhalt der Playlist einfügen, **Speichern**. Über **Einrichtungsseite öffnen** lassen sich im Browser Omas Sender auswählen und sortieren.
- **Umschalten:** nach oben oder unten wischen, oder oben bzw. unten auf das Bild tippen.
- **Senderliste:** in die Mitte tippen, mit dem Finger scrollen, Sender antippen.
- **Einstellungen:** lange auf das Bild drücken, oder in der Senderliste unten auf **⚙ Einstellungen** tippen.

## Gut zu wissen

- **Noch nicht auf einem echten Gerät gelaufen.** Geprüft sind das Lesen der Senderlisten, die Handy-Seite (im Browser) und die Abläufe der App in einer Simulation. Die APK selbst entsteht erst bei GitHub. Hakt am Fernseher etwas, sag mir Bescheid.
- **Updates:** dieselbe Adresse im Downloader erneut öffnen und installieren. Einstellungen und Senderauswahl bleiben erhalten.
- Bewusst weggelassen: Programmzeitschrift (EPG), Aufnahme, Filme und Serien aus der Liste (werden ausgeblendet).
- Die Einrichtungsseite kann jeder im selben WLAN öffnen, solange Oma TV läuft.
- Der Signaturschlüssel der App liegt im Projekt und ist damit öffentlich. Für eine private App ist das unkritisch; wer es nicht möchte, stellt das GitHub-Projekt auf „Private“ und lädt die APK angemeldet von der Projektseite unter „Releases“.
