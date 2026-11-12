# Oma TV – Anleitung

Eine Fernseh-App mit genau einer Aufgabe: Omas Sender zeigen und mit ▲▼ umschalten. Keine Werbung, kein Abo, keine Menüs. Eingerichtet wird alles bequem vom Handy aus.

**Du brauchst:** einen Google-TV-Stick am Fernseher, ein kostenloses GitHub-Konto und etwa 20 Minuten.

---

## 1. Die App bauen lassen (einmalig, kostenlos)

GitHub baut aus dem Projekt automatisch die Installationsdatei (APK). Du musst nichts programmieren.

1. Auf [github.com](https://github.com) anmelden oder kostenlos registrieren.
2. Oben rechts **+** → **New repository**. Name `oma-tv`, Sichtbarkeit **Public**, dann **Create repository**.
3. Auf der leeren Projektseite den Link **uploading an existing file** anklicken, die Datei `oma-tv-projekt.zip` auswählen, unten **Commit changes**.
4. **Add file** → **Create new file**. Als Dateinamen exakt eintippen:
   `.github/workflows/build.yml`
   Den kompletten Inhalt der mitgelieferten Datei `build.yml` hineinkopieren, dann **Commit changes**.
5. Reiter **Actions** öffnen. Dort läuft jetzt „APK bauen“. Nach etwa 3–5 Minuten erscheint ein grüner Haken.
6. Die fertige App liegt ab jetzt immer unter dieser Adresse (DEIN-NAME ersetzen):
   `github.com/DEIN-NAME/oma-tv/releases/latest/download/oma-tv.apk`

Erscheint ein rotes ✕ statt des grünen Hakens: den Lauf anklicken, die rot markierte Zeile kopieren und mir schicken. Dann behebe ich das.

## 2. Auf dem Stick installieren

1. Stick normal einrichten (WLAN, Google-Konto).
2. Im Play Store auf dem Stick die App **Downloader** (von AFTVnews) installieren und öffnen.
3. Die Adresse aus Schritt 1.6 eintippen und **Go** drücken.
4. Der Stick fragt einmalig, ob Downloader Apps installieren darf: erlauben, dann **Installieren**. Eine Warnung vor „unbekannter App“ mit „Trotzdem installieren“ bestätigen.
5. **Oma TV** öffnen.

Falls du Downloader nicht findest: die APK am Handy über dieselbe Adresse herunterladen und mit der App „Send Files to TV“ (auf Handy und Stick installieren) übertragen.

## 3. Sender einrichten – vom Handy

Beim ersten Start zeigt der Fernseher eine Adresse wie `http://192.168.178.50:8080`.

1. Handy ins gleiche WLAN bringen und die Adresse im Browser öffnen.
2. **M3U-Link** einfügen → **Speichern und Sender laden**. Mehrere Links: einer pro Zeile.
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

## Gut zu wissen

- **Noch nicht auf einem echten Gerät gelaufen.** Geprüft sind das Lesen der Senderlisten, die Handy-Seite (im Browser) und die Abläufe der App in einer Simulation. Die APK selbst entsteht erst bei GitHub. Hakt am Fernseher etwas, sag mir Bescheid.
- **Updates:** neue `oma-tv-projekt.zip` ins GitHub-Projekt hochladen (alte ersetzen), warten, dieselbe Adresse im Downloader erneut öffnen. Einstellungen und Senderauswahl bleiben erhalten.
- Bewusst weggelassen: Programmzeitschrift (EPG), Aufnahme, Filme und Serien aus der Liste (werden ausgeblendet).
- Die Einrichtungsseite kann jeder im selben WLAN öffnen, solange Oma TV läuft.
- Der Signaturschlüssel der App liegt im Projekt und ist damit öffentlich. Für eine private App ist das unkritisch; wer es nicht möchte, stellt das GitHub-Projekt auf „Private“ und lädt die APK angemeldet von der Projektseite unter „Releases“.
