# CutLab

Ein einfacher Video-Editor für Android, den man selbst bauen kann. Gedacht für Kurzvideos: schneiden, Text, Bilder, Musik und automatische Untertitel. Ohne Abo und ohne Schnickschnack.

## Installieren

1. Auf GitHub im Reiter **Releases** die neueste `CutLab-build-….apk` herunterladen.
2. Auf dem Handy antippen und die Installation erlauben.
3. Wer ein Update installieren will, muss die alte Version vorher deinstallieren (dabei gehen die Projekte in der App verloren, exportierte Videos nicht).

Bei jedem Upload auf GitHub baut sich die App automatisch neu und erscheint als neues Release.

## Was die App kann

**Schneiden**
- Mehrere Videos hintereinander legen und die Reihenfolge ändern
- Am Playhead teilen, Anfang und Ende per Ziehen kürzen
- Standbilder auf den Clips zeigen, was wo passiert
- Timeline mit zwei Fingern rein- und rauszoomen (oder mit der Lupe); den Griff über der Timeline nach oben/unten ziehen, um sie größer oder kleiner zu machen

**Spuren**
- Text, Bilder und Musik als eigene Spuren unter dem Video
- Zeitlich verschieben und in der Länge ziehen
- Text und Bilder direkt in der Vorschau verschieben und mit zwei Fingern skalieren
- Hilfslinien (Mitte, Ränder, goldener Schnitt): Elemente rasten sanft ein, lassen sich aber trotzdem weiterziehen

**Untertitel automatisch**
- Sprache wählen, die App erkennt das Gesagte (10 Sprachen, läuft offline)
- Beim ersten Mal wird ein kleines Sprachmodell geladen (ca. 40–50 MB)
- Drei Stile: Klassisch, Karaoke (aktuelles Wort farbig) und Pop-up (aktuelles Wort poppt auf)
- Größe, Position (auch links/rechts, mit Einrasten in der Mitte) und Farbe gelten für alle Untertitel auf einmal
- Zu lange Wörter werden getrennt, der Text bleibt immer im Bild
- Eigene **Vorlagen** speichern (mit Namen) und eine als Standard für neue Projekte festlegen

**Projekte**
- Alles wird automatisch gespeichert
- Projektliste über das Ordner-Symbol oben links: neu, öffnen, umbenennen, löschen

**Vorschau und Export**
- Die Vorschau zeigt alles so, wie es später aussieht, und lässt sich mit zwei Fingern heranzoomen
- „Rückgängig" für die letzten Schritte
- Export als MP4 in 9:16 (hochkant), 16:9 oder 1:1, gespeichert unter Filme/CutLab

## Kurz bedienen

- **Timeline:** wischen bewegt den Playhead (die weiße Linie in der Mitte)
- **Antippen:** Element auswählen, unten erscheinen dann Einstellungen und der Mülleimer
- **Weiße Ränder ziehen:** kürzen
- **Lange drücken und ziehen:** verschieben
- **Untermenü unten:** Video, Teilen, Text, Bild, Musik, Untertitel

## Was noch fehlt

- Lautstärke pro Clip oder Musikspur
- Wellenform bei der Musik
- Übergänge und Filter

## Für Entwickler

Kotlin und Jetpack Compose. Vorschau mit Media3 ExoPlayer, Export mit Media3 Transformer, Spracherkennung mit Vosk. Text, Bilder und Untertitel werden von demselben Code gezeichnet, in der Vorschau wie im Export. Mit Android Studio lässt sich der Ordner direkt öffnen.
