# ALBO Connect für iPhone, iPad und Android

Dieses Paket enthält den nativen SwiftUI-Quellcode und das Xcode-Projekt nach deiner Bildvorlage. Der GitHub-Build vom 9. November 2026 war erfolgreich: sieben native Tests und sieben Server-Tests bestanden; eine echte arm64-Geräte-IPA wurde erstellt. **Die IPA ist noch unsigniert und lässt sich ohne eigene Apple-Signierung nicht direkt auf einem normalen iPhone installieren.** Bedienung, Spracheingabe und Netzwerkabgleich auf einem echten Gerät bleiben zu prüfen.

Im Quellcode umgesetzt:

- Startseite mit ALBO-Logo, sechs Kacheln und unterer Navigation.
- Baustellen anlegen, bearbeiten und Kunden zuordnen.
- Arbeitszeiten pro Mitarbeiter starten, pausieren, fortsetzen und beenden; lokale Speicherung über App-Neustarts. Nettozeit und tatsächliches Ende werden getrennt gespeichert.
- Berichte manuell oder mit Vorlagen schreiben; Diktat und professionelle Formulierung auf dem iPhone nutzen; interne Notizen bleiben im Team und erscheinen nicht im Kunden-PDF; digitale Unterschriften von Kunde und Monteur.
- Mikrofon-Diktat über die Apple-Spracherkennung, mit Berechtigungsabfrage und Behandlung fehlender Verfügbarkeit. Je Aufnahme maximal 55 Sekunden; weitere Aufnahmen können angehängt werden.
- Lokale KI-Formulierung mit Apple Foundation Models, falls Apple Intelligence auf dem Gerät verfügbar ist. Ohne lokale KI bleibt der diktierte Text bearbeitbar. KI-Ergebnisse müssen vor der Freigabe geprüft werden.
- Fotos aus der Fotomediathek und von der Kamera, mit Bildbeschreibungen und Zuordnung zum Bericht.
- Aufmaß mit Länge, Breite, Anzahl, Flächenberechnung und Übernahme in den Bericht.
- Kunden- und Monteurunterschrift mit Finger/Stift.
- Mehrseitige PDF-Berichte mit Text, Material, Aufmaß, Fotos und Unterschriften; Teilen/Sichern über iOS.
- Kunden-, Mitarbeiter- und Vorlagenverwaltung auf dem Gerät, mit breiter Ansicht auf dem iPad.
- Datensicherung und Wiederherstellung einschließlich Fotoanhängen und Unterschriften.

Zusätzlich ist eine **echte lokale PC-Verwaltung** im Ordner `pc` enthalten. Speicherung, Geräte-Kopplung, Mitarbeiterberechtigungen, Widerruf von Gerätezugängen und das Zusammenführen paralleler Änderungen wurden unter Windows getestet. Der dazugehörige native iPhone-Abgleich ist kompiliert; der vollständige Netzwerkabgleich mit einem echten iPhone ist noch nicht geprüft. Es handelt sich um eine manuell ausgelöste Synchronisierung, keine laufende Cloud-Verbindung.

## PC-Verwaltung jetzt unter Windows testen

1. Das komplette ZIP entpacken.
2. Im Unterordner `pc` die Datei **Start-Windows.cmd** doppelklicken. Sie nutzt eine vorhandene Node.js-Installation oder die vorhandene Codex-Node-Laufzeit. Andernfalls wird Node.js 22 oder neuer benötigt.
3. Das Konsolenfenster offen lassen. Die Verwaltung öffnet sich im Browser unter `http://localhost:8787`.
4. Beim ersten Öffnen ein Administratorkonto mit eigenem Benutzernamen und mindestens 12 Zeichen langem Passwort anlegen. Dieses erste Konto kann nur direkt auf dem PC über localhost erstellt werden.
5. Mit dem Administratorkonto anmelden. Unter **Benutzer** weitere Konten erstellen, als **Verwaltung** oder **Mitarbeiter** einordnen und Mitarbeiterkonten einem aktiven Mitarbeiter zuweisen. Konten können bearbeitet, deaktiviert und mit einem neuen Passwort versehen werden. Mindestens ein aktives Administratorkonto muss bestehen bleiben.
6. Baustellen, Kunden, Mitarbeiter, eigene Vorlagen und Berichte anlegen. Unter **Datensicherung** lässt sich außerdem ein eigenes Logo als PNG, JPG oder WebP (bis 10 MB) hochladen. Den gewünschten Ausschnitt aufziehen und speichern; das Bild oben links in der PC-Verwaltung wird dadurch ersetzt. Änderungen bleiben nach einem Server-Neustart erhalten. Der Datenordner ist `pc/data`; dort werden keine Beispieldaten vorausgesetzt.
7. Berichte über **PDF / Drucken** in der Druckansicht öffnen und im Browser als PDF speichern.

Passwörter werden nicht im Klartext gespeichert. Nach acht fehlgeschlagenen Anmeldeversuchen von einer Adresse wird die Anmeldung dort für 15 Minuten gesperrt. Mitarbeiterkonten können in der PC-Verwaltung nur Berichte und Arbeitszeiten bearbeiten; Stammdaten und Benutzer verwaltet ein Administratorkonto.

Alternativ im Terminal:

```powershell
node pc/server.mjs --open
```

Die Symbole sind lokal mitgeliefert; die PC-Oberfläche braucht kein externes CDN. Die Lizenz liegt unter `pc/vendor/LUCIDE-LICENSE.txt`.

## iPhone mit dem PC verbinden — Gerätetest noch offen

Für den Zugriff über Mobilfunk von Baustellen aus ist der private NetBird-Modus für 1–5 Personen vorbereitet. Einstieg: **[BAUSTELLEN-ZUGRIFF.md](BAUSTELLEN-ZUGRIFF.md)**. Er trennt die lokale PC-Verwaltung von einem ausschließlich mit Kopplungsschlüssel erreichbaren Datenendpunkt. NetBird-Installation, Kontoanmeldung und echter Mobilfunktest sind noch offen.

Für den regulären Netzwerkbetrieb unterstützt der Server HTTPS mit einem vertrauenswürdigen Zertifikat. Die Dateipfade werden über `ALBO_TLS_KEY` und `ALBO_TLS_CERT` gesetzt; anschließend den Server mit `--lan` starten. Diese Einrichtung verändert weder Firewall noch Router automatisch.

Für einen ausdrücklich unverschlüsselten Test im vertrauten lokalen Netz:

```powershell
node pc/server.mjs --lan --allow-insecure-lan
```

1. Auf dem PC die Verwaltung über localhost öffnen und unter **Geräte koppeln** einen Zugang erstellen. **Mitarbeiter** darf eigene Berichte und Arbeitszeiten bearbeiten, **Verwaltung** darf auch Stammdaten pflegen.
2. In der nativen App unter **Mehr → PC-Verbindung** die Netzwerkadresse des PCs und den einmalig angezeigten Kopplungsschlüssel eintragen. Auf dem iPhone ist `localhost` nicht der PC; als lokale Testadresse beispielsweise `http://192.168.1.20:8787` verwenden, mit der tatsächlichen PC-Adresse.
3. **Verbinden und synchronisieren** antippen. Der Schlüssel wird im iOS-Schlüsselbund gespeichert. Ein leeres Gerät lädt den PC-Datenbestand. Vorhandene lokale Daten benötigen beim Erstabgleich einen Verwaltungszugang.
4. Nach Änderungen erneut synchronisieren und die PC-Ansicht aktualisieren. Unabhängige Datensätze werden zusammengeführt. Gleichzeitige Änderungen am selben Datensatz werden abgewiesen; lokale Änderungen bleiben erhalten und können gesichert werden.

Der Server bestätigt den jeweiligen Ausgangsstand kryptografisch. Dadurch kann ein Gerät auch nach längerer Offline-Zeit synchronisieren, ohne alte Änderungen anderer Geräte als eigene Löschungen zu behandeln. Ein Konflikt wird nicht automatisch zugunsten eines Geräts überschrieben.

Die PC-Datenbank und Kopplungsschlüssel gehören nicht in GitHub. `pc/data`, Zertifikate und Buildprodukte sind vom Projektarchiv ausgeschlossen.

## Voraussetzungen

iOS/iPadOS 17 oder neuer. Für die KI-Funktion: Xcode 26 oder neuer zum Bauen sowie ein unterstütztes Gerät mit verfügbarer Apple Intelligence. Bei älterem SDK wird die KI-Funktion nicht mitkompiliert; die übrigen Funktionen bleiben im Quellcode erhalten.

## Build auf einem Mac

1. `ALBOConnect.xcodeproj` öffnen.
2. Das Schema **ALBOConnect** und einen iPhone- oder iPad-Simulator wählen.
3. Mit **Product → Test** die Tests ausführen, anschließend mit **Run** die App starten.
4. Für ein echtes Gerät unter **Signing & Capabilities** ein eigenes Team und eine eindeutige Bundle-ID eintragen. Diese Angaben sind absichtlich nicht mit einem fremden Konto vorbelegt.
5. Mikrofon, Kamera und Apple Intelligence auf einem echten Gerät prüfen. Der Simulator ersetzt diese Prüfung nicht.

Alternativ vom Terminal im Projektordner:

```bash
bash scripts/test-on-mac.sh
```

## Builddienst bei ausschließlich Windows

Die Datei `.github/workflows/apple-build.yml` führt Tests und einen Geräte-Build auf einem macOS-Runner aus. Der erste erfolgreiche Lauf ist [hier dokumentiert](https://github.com/Ro-ma-n-87/albo/actions/runs/42670462238).

1. Das Projekt liegt für das öffentliche Repository `Ro-ma-n-87/albo` vor. Die öffentliche Veröffentlichung von Quellcode, ALBO-Grafik und Build-Konfiguration wurde vom Nutzer ausdrücklich freigegeben.
2. Unter **Actions → Apple App Build → Run workflow** starten.
3. Buildprotokoll und Testergebnis prüfen. Erst nach erfolgreichen Windows- und Simulator-Tests startet der Geräte-Build.
4. Das Artefakt **ALBOConnect-unsigned-IPA** herunterladen. Nach dem Entpacken enthält es `ALBOConnect-unsigned.ipa`, eine Prüfsumme und einen Hinweis zum Signierungsstatus.

Die Geräte-IPA wird aus dem `iphoneos`-Build erstellt. Das Skript prüft Plattform, arm64-Code und die Payload-Struktur. Eine Simulator-App wird nicht einfach in `.ipa` umbenannt.

**Die erzeugte IPA ist unsigniert und lässt sich nicht direkt auf einem normalen iPhone installieren.** Für die Installation ist die eigene Apple-Signierung mit dem passenden Bereitstellungsprofil erforderlich. Für TestFlight sind zudem App Store Connect und der entsprechende Distributionsweg einzurichten. Zugangsdaten und Signierungsschlüssel gehören nicht in den Quellcode und nicht in diesen Chat.

Apple beschreibt die notwendigen Signierungs- und Geräteprofile unter [Distributing your app to registered devices](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices).

## Prüfnachweis und offene Arbeit

Siehe `STATUS.md`. Apple-Build und Logiktests sind nachgewiesen. Apple-Signierung, Installation, echte Gerätetests, ein visueller Vergleich auf iPhone/iPad und der vollständige Abgleich zwischen echten iPhones und PC sind noch offen.

Die Grafik `Reference.imageset/reference.png` ist die Bildvorlage der nativen iPhone-App. Die PC-Verwaltung kann unabhängig davon ein eigenes Logo für die Seitenleiste verwenden.

Technische Grundlage: [Apple-Spracherkennung](https://developer.apple.com/documentation/speech/sfspeechaudiobufferrecognitionrequest), [Apple Foundation Models und Verfügbarkeitsprüfung](https://developer.apple.com/documentation/foundationmodels/systemlanguagemodel).
## Android-App

Unter `android` liegt die eigenständige Kotlin-/Jetpack-Compose-App für Android. Sie speichert lokale Daten verschlüsselt im privaten App-Speicher und funktioniert für Baustellen, Stempeln und Berichtsentwürfe auch offline. Spracheingabe läuft über die Android-Spracherkennung. Die PC-Verbindung übernimmt den Mitarbeiter-Kopplungsschlüssel und gleicht Änderungen über denselben Server-Endpunkt wie die iPhone-App ab.

Auf diesem Windows-PC ist kein Android-SDK installiert. Zum Öffnen `android` in Android Studio laden und dort Gradle synchronisieren. Für einen APK-Build ist der Workflow **Android App Build** unter GitHub Actions eingerichtet; er erzeugt eine Debug-APK als herunterladbares Artefakt. Für Verteilung außerhalb interner Tests ist danach ein eigener Release-/Signierungsprozess einzurichten.
