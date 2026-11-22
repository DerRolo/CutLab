# CutLab

Ein einfacher Video-Editor für Android, den man selbst bauen kann. Gedacht für Kurzvideos: schneiden, Text, Bilder, Musik und automatische Untertitel. Ohne Abo und ohne Schnickschnack.

## Installieren

1. Auf GitHub im Reiter **Releases** die neueste `CutLab-build-….apk` herunterladen.
2. Auf dem Handy antippen und die Installation erlauben.
3. Wer ein Update installieren will, muss die alte Version vorher deinstallieren (dabei gehen die Projekte in der App verloren, exportierte Videos nicht).

Bei jedem Upload auf GitHub baut sich die App automatisch neu und erscheint als neues Release.

## Was die App kann

**Schneiden**
- Clips liegen frei auf der Zeitleiste: nach rechts schieben (Lücken sind erlaubt, nur 0 s ist das Minimum) und beim Verschieben rastet alles sofort ein (gelbe Linie)
- Ein Clip über einen anderen gezogen kappt den darunterliegenden an dieser Stelle (wie bei DaVinci)
- Beim Verschieben und Kürzen zeigt ein Feld Start- und Endzeit an
- Am Playhead teilen, Anfang und Ende per Ziehen kürzen
- Standbilder auf den Clips zeigen, was wo passiert
- Timeline mit zwei Fingern rein- und rauszoomen (oder mit der Lupe); den Griff über der Timeline nach oben/unten ziehen, um sie größer oder kleiner zu machen

**Mehrere Videospuren (Overlay)**
- Mit „Overlay" legst du ein weiteres Video auf die erste freie Videospur (Video 2, Video 3, …). Höhere Spuren liegen oben, **Video 1** (Hauptspur) bleibt immer unten
- Es gibt anfangs nur **Video 1**. Zieht man ein Element (auch einen Clip) nach oben, entsteht eine neue Spur; nach unten zurückziehen macht es wieder zum Hauptclip. Pro Zug entsteht höchstens eine neue Spur
- Links in der Timeline stehen die Spurnamen (Video 1/2/3, Untertitel, Text 1/2, Bild 1/2, Ton 1/2); Ton-Spuren wachsen nach unten
- Element lange drücken und nach oben/unten ziehen wechselt die Spur; ganz an den Rand gezogen entsteht eine neue Spur
- Frei verschiebbar und kürzbar wie alle anderen Elemente, rastet an Nachbarn ein
- In der Vorschau mit einem Finger verschieben und mit zwei Fingern skalieren (Bild-in-Bild)
- Ton an/aus, Ein- und Ausblenden (Fade 0,3 / 0,6 / 1 s) für weiche Übergänge
- Der Export dauert mit Overlay-Videos deutlich länger

**Spuren**
- Text, Bilder und Musik als eigene Spuren unter dem Video
- Feinmodus: Element lange drücken oder Anfang/Ende anfassen – die Timeline zoomt automatisch heran und springt an die Kante, nach dem Loslassen ist der Zoom wieder wie vorher
- Zeitlich verschieben und in der Länge ziehen; Elemente rasten an Nachbarn, am Playhead und an Clip-Grenzen ein (gelbe Linie)
- Text und Bilder direkt in der Vorschau verschieben und mit zwei Fingern skalieren
- Hilfslinien (Mitte, Ränder, goldener Schnitt): Elemente rasten sanft ein, lassen sich aber trotzdem weiterziehen

**Untertitel automatisch**
- Drei Genauigkeitsstufen: **Whisper small** (am genauesten, empfohlen), **Whisper base** (schneller) und **Vosk** (einfach, oft ungenau)
- Sprache wählen oder automatisch erkennen lassen; Namen und besondere Wörter (z. B. Städte) lassen sich vorgeben, damit sie richtig geschrieben werden
- Läuft offline; das Sprachmodell (Deutsch, Englisch und viele weitere Sprachen) wird beim allerersten Start der App einmalig geladen (ca. 190 MB, WLAN empfohlen)
- Drei Stile: Klassisch, Karaoke (aktuelles Wort farbig) und Pop-up (aktuelles Wort poppt auf)
- Größe, Position (auch links/rechts, mit Einrasten in der Mitte), Schrift-, Hervorhebungs- und Konturfarbe gelten für alle Untertitel auf einmal
- Untertitel oder Text antippen: am weißen Rahmen in der Vorschau erscheint ein **Stift** (Text bearbeiten) und ein **Mülleimer**; dieselben Knöpfe gibt es oben links in der Timeline
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

Kotlin und Jetpack Compose. Vorschau mit Media3 ExoPlayer, Export mit Media3 Transformer, Spracherkennung mit whisper.cpp (nativ, per CMake) und Vosk. Text, Bilder und Untertitel werden von demselben Code gezeichnet, in der Vorschau wie im Export. Mit Android Studio lässt sich der Ordner direkt öffnen.

**Hinweis zu Whisper:** Der Quellcode von whisper.cpp liegt nicht im Repo, sondern wird beim GitHub-Build automatisch nach `app/src/main/cpp/whisper.cpp` geladen. Wer lokal in Android Studio baut, macht einmalig: `git clone --depth 1 --branch v1.7.2 https://github.com/ggerganov/whisper.cpp.git app/src/main/cpp/whisper.cpp`. Ohne diesen Ordner baut die App trotzdem und nutzt nur Vosk. Die App läuft nur auf 64-Bit-Handys (arm64).
