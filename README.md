# CutLab – einfacher Video-Editor für Android

Mehrspur-Timeline, Text-/Bild-/Musikspuren, automatische Untertitel (mehrere Sprachen, 3 Stile), Export als MP4.

## Starten
1. Neues GitHub-Repo anlegen, diesen Ordnerinhalt hochladen (`git push` auf `main`).
2. Unter **Actions → Build APK** läuft der Build automatisch. Danach das Artefakt `cutlab-debug-apk` herunterladen und die APK aufs Handy installieren.
3. Alternativ: Ordner in **Android Studio** öffnen und auf dem Handy starten (zum Debuggen empfohlen).

## Bedienung
- **Projekte**: Ordner-Symbol oben links öffnet die Projektliste (neu, öffnen, umbenennen, löschen). Auf den Projektnamen tippen = umbenennen. Alles wird automatisch gespeichert.
- **Vorschaubilder**: Videoclips zeigen in der Timeline Standbilder (1 pro Sekunde Quellvideo, werden im Hintergrund geladen).
- **Timeline**: wischen = Playhead bewegen (Mitte). Element antippen = auswählen, weiße Ränder ziehen = kürzen, lange drücken + ziehen = verschieben (Videoclips: Reihenfolge ändern).
- **Teilen**: Playhead an die Stelle, dann „Teilen“.
- **Text/Bild**: in der Vorschau mit einem Finger verschieben, mit zwei Fingern skalieren.
- **Untertitel**: Sprache wählen → Modell wird einmalig geladen → Wörter werden mit Zeitstempeln erkannt. Stil, Größe, Position und Farbe gelten für alle Untertitel. Stile: Klassisch, Karaoke (aktuelles Wort farbig), Pop-up (aktuelles Wort poppt auf).

## Technik
Kotlin + Jetpack Compose · Media3 ExoPlayer (Vorschau) · Media3 Transformer (Export) · Vosk (Offline-Spracherkennung).
`OverlayPainter` zeichnet Text/Bilder/Untertitel – derselbe Code für Vorschau und Export.

## Bekannte Grenzen / nächste Schritte
- Projekt wird automatisch gespeichert; importierte Dateien werden in den App-Speicher kopiert (über „+“ oben wird alles gelöscht und neu begonnen).
- Keine Wellenformen für Musik, keine Übergänge/Filter.
- Sprachmodelle: Namen/Versionen in `SubtitleGenerator.kt` (`VoskModels`), Liste auf https://alphacephei.com/vosk/models
- Falls `addGap` im Exporter nicht kompiliert: Media3-Version in `app/build.gradle.kts` anpassen.
