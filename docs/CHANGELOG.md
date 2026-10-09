# Änderungsverlauf

Verlauf der MVP-Versionen. Verweise auf PLAN.md beziehen sich auf ein internes Planungsdokument, das nicht Teil dieses Repos ist.

## Version 0.2.8-mvp

Fehler behoben: Der Checkup scrollte in 0.2.7 nicht und meldete fälschlich "Listenende erreicht". Die Listenwahl nimmt jetzt den naechsten scrollbaren Vorfahr der Chatzeilen oder deren Geometrie (Seitenwechsler und waagerechte Listen nie), der Wisch laeuft auch ohne Listenknoten, "Listenende" gilt nur nach einem Scrollversuch mit unveraendertem Inhalt, sonst "Scrollen nicht moeglich" mit Fehlerstatus und Warnung. Neues Logo (Entwurf 2a): Sprechblase im Verlauf Tuerkis nach Violett mit drei ausgesparten KI-Sternen, ohne Lupe.

Tests: `./gradlew :app:testDebugUnitTest` (355 Tests, 1 uebersprungen). Alles ungetestet auf dem Geraet, Details in PLAN.md Abschnitt 22.

## Version 0.2.7-mvp

Neu: Der schwebende Punkt lässt sich im Ring mit "Entfernen" ausschalten (Wiedereinschalten: Einstellungen, "Schwebender Punkt"). Neues Logo (Lupe mit Sprechblase und Funke, drei Varianten, Standard B, umschaltbar mit `python3 tools/make_logo.py --default a|b|c`). "Analysieren" liest den Chat und fragt dann, ob wie immer oder mit eigenem Prompt ausgewertet wird. Fortschrittsanzeige mit Schrittleiste, Zähler und Sekunden im Modellschritt (kein Token-Zähler, weil nicht gestreamt wird). Neuer Modelltab mit kompakten Zeilen und Punktbewertungen (unsere Einschätzung, getrennt von gemessenen Werten). Parakeet lädt beim Einschalten der Sprachnachrichten selbst (WLAN, bei mobilen Daten mit Rückfrage). Der Tab "Analyse" (Auftrag) ist aufgelöst, seine Einstellungen stehen jetzt unter Einstellungen, der Status auf der Startseite. Steckbrief je Chat bis 6000 Zeichen in 9 Abschnitten, DISC-Einschätzung des Gegenübers (vorsichtige Schätzung), Ich-Profil (chatübergreifender Stil des Nutzers, mit Filter gegen Chat-Inhalte) und die Selbstanalyse über mehrere Chats mit Bestätigung vor der Übernahme. Setup, Selbstanalyse und Auto sind erst nach einem Checkup nutzbar (Assistent auf der Startseite).

Tests: `./gradlew :app:testDebugUnitTest` (337 Tests, 1 übersprungen). Renderbilder landen in `app/build/renders`. Alles ungetestet auf dem Gerät, Details in PLAN.md Abschnitt 21.

## Version 0.2.6-mvp

Neu: Sprachnachrichten werden lokal transkribiert. Modell: NVIDIA Parakeet TDT 0.6B v3 (25 europäische Sprachen inklusive Deutsch, Lizenz CC-BY-4.0, Namensnennung NVIDIA; ONNX-Umwandlung von k2-fsa/csukuangfj), INT8, vier Dateien mit zusammen 670478772 Byte, im Tab Modelle ladbar (Katalogeintrag mit SHA-256 je Datei, feste Hugging-Face-Revision). Laufzeit: sherpa-onnx 1.13.8 (JitPack), Opus-Dekodierung mit eigenem Ogg-Leser und Concentus (Java), Rückfall MediaCodec. Die Audiodateien kommen aus dem per Ordnerfreigabe (Storage Access Framework) gewählten Ordner `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes`; die Zuordnung Nachricht zu Datei läuft über Uhrzeit und Dauer. Der Text steht danach als "Transkript" im Verlauf für das LLM. Einstellungen: Abschnitt "Sprachnachrichten" (Schalter, Ordner, Limits, Selbsttest). Log: Zeilen `STIMME:` und Abschnitt "Sprachnachrichten" im Markdown-Log, ohne Texte und Dateinamen. APK 104669932 Byte (plus 32,9 MB, wegen der nativen Bibliotheken). 266 Unit-Tests, darunter echte Erkennung mit dem Modell auf der Entwicklungsmaschine (übersprungen ohne Modell). Auf dem Telefon nicht getestet: Ordnerfreigabe unter HyperOS, Zuordnung mit echten Dateien, Geschwindigkeit und Speicher. Siehe PLAN.md Abschnitt 20.

Namensnennung (CC-BY-4.0): Parakeet TDT 0.6B v3 von NVIDIA, https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 ; sherpa-onnx von k2-fsa (Apache-2.0); Concentus (BSD-3-Clause).

## Version 0.2.5-mvp

Neu: Checkup und Auswahlmenü. Der Checkup liest die obersten X Chats der Chatliste (Standard 50, nur senkrecht im Sicherheitsfenster, kein Chat wird geöffnet, Tab Chats wird geprüft), beim Start der App (Schalter, Standard an) oder per Knopf "Checkup jetzt". Im Menü kreuzt man Chats an (Suche, Alle, Keine, Reihenfolge, neu markiert, frühere Auswahl vorgewählt, Top 10, 20, 30 als Schnellwahl). Das Setup bearbeitet nur die angekreuzten Chats. Die Auswahl (nur Namen) wird verschlüsselt gespeichert. Siehe ../PLAN.md Abschnitt 19. Auf dem Gerät nicht getestet.

## Version 0.2.4-mvp

Fehlerbehebung nach dem Gerätetest von 0.2.3: Wischer strikt im Sicherheitsfenster (12 Prozent Randabstand, Statusleiste plus 150 px, Gestenzone plus 250 px, nur senkrecht, höchstens zwei Wischer), Prüfung nach jedem Wisch mit Schließen von Benachrichtigungsleiste oder Launcher und Auslöser im Log, nie Tabs blättern (Seitenwechsler ausgeschlossen, nur ACTION_SCROLL_UP/DOWN), Tab Chats wird geprüft und gezielt angetippt, Öffnen ohne zweiten Tipp, Kurznamen-Diagnose, Fehlerstatus mit Grund und Pause nach 2 Fehlern, Markdown-Log (Knöpfe im Tab Debug und im Tab Start, automatische Speicherung in `runlogs`). Siehe ../PLAN.md Abschnitt 18. Auf dem Gerät nicht getestet.

## Version 0.2.3-mvp

Fehlerbehebung nach dem Gerätelog von 0.2.2: Verschlüsselung (Android Keystore verbietet vorgegebene IVs beim Verschlüsseln; Gedächtnis und Warteschlange konnten nie gespeichert werden), Vordergrundwächter (vor jedem Lesen, Klicken und Zurück wird geprüft, dass WhatsApp vorn ist; sonst bis zu 3 Mal per Intent zurückholen, dann sauberer Abbruch, nie in fremden Fenstern klicken), Chatliste (Erkennung über Knoten-IDs, Zurück nur aus Chat oder aktiver Suche, Zeilen über IDs, automatischer Debug-Baum im Tab Debug), Kontrast (heller Text auf dunklem Glas, Karten 70 Prozent deckend, WCAG AA per Test). Siehe ../PLAN.md Abschnitt 17. Auf dem Gerät nicht getestet. Vorschaubilder: `./gradlew :app:testDebugUnitTest --tests 'app.chatlens.render.RenderTest'`.

## Version 0.2.2-mvp

Neu: Qwen3.5 im Modellkatalog (4B gemischt INT4 als Vergleichskandidat zu Gemma 4 E4B, 4B INT8 experimentell, 0.8B INT8 nur CPU; 2B bleibt experimentell). Alle mit vollem Commit und LFS-SHA-256 aus der Hugging-Face-API, nur Text, KV-Budget 4096 Token (Zeichengrenze 8988), "Deutsch nicht belegt" gekennzeichnet. Empfehlungskarte zeigt neben dem Standard den Vergleichskandidaten. `/no_think` wird nur noch bei Qwen3 angehängt, nicht bei Qwen3.5. Qwen3.6 hat keine `.litertlm`-Datei und ist nicht aufgenommen (PLAN.md 16.10). Details: PLAN.md 16.9 bis 16.13. Auf dem Gerät ungetestet.

## Version 0.2.1-mvp

Neu: Tab "Modelle" (Modellkatalog). Gemma 4 E4B (Standard), Gemma 4 E2B und Qwen3-Varianten (LiteRT-LM) mit Größe, Lizenz, RAM-Bedarf, Eignung und Empfehlung nach Gerät; Download mit Fortschritt, Pause, Fortsetzen, SHA-256, Speicher- und WLAN-Prüfung; Quelle öffnen; Verwenden als Backend; gated Modelle nur als Link; Parakeet als Vorschau mit Link. Katalog: `app/src/main/assets/model-catalog.json`, geprüft mit `tools/verify_catalog.py --check` (Hugging-Face-API). Details: PLAN.md Abschnitt 16. Auf dem Gerät ungetestet.

Echter Download-Test (optional, 345 MB): `CHATLENS_NET_TEST=1 ./gradlew :app:testDebugUnitTest --tests 'app.chatlens.models.RealDownloadTest'`.

## Version 0.2.0-mvp

Neu: Setup als erster Schritt (die neuesten 10, 20, 30 oder frei gewählte Zahl an Chats werden automatisch aus der Chatliste geöffnet, gelesen und als Profil im Gedächtnis angelegt; Fortschritt, Abbruch, Fehler je Chat überspringen, Wiederaufnahme, Übersicht), Namensabgleich mit Rückfrage, Auto-Modus (inkrementell), verschlüsseltes Gedächtnis (Android Keystore, AES-GCM, Export und Löschen), Berater, Antwortvorschläge (nichts wird automatisch gesendet; Eintragen ins Eingabefeld; experimenteller Senden-Knopf aus und hinter Dialog), schwebender Punkt mit Ring (Overlay), Glas-Design, Vorbereitung für Sprachnachrichten (nur Schnittstelle). Details: PLAN.md Abschnitt 15, Installation: INSTALL-XIAOMI.md 5a und 5b. Auf dem Gerät ungetestet.

Vorschau der Oberfläche ohne Emulator: `./gradlew :app:testDebugUnitTest --tests 'app.chatlens.render.RenderTest'` schreibt PNG-Dateien nach `app/build/renders`.

## Version 0.1.4-mvp

Ende des geladenen Verlaufs: Erkennung "Inhalt unverändert" (kein Überlappungsverlust, kein Moduswechsel), Warten auf Nachladen bis 6 s mit bis zu 3 Wischversuchen, sauberes Ende mit Grund und anschließender Analyse des Erfassten, harte Grenzen (4 Schritte ohne neue Zeilen, 6 Wiederherstellungen ohne Fortschritt), Gegenschritt mit Verschiebung 0 zählt nicht als Erfolg, Debug-Schalter "Bei unverändertem Inhalt beenden". Details: PLAN.md Abschnitt 14. Auf dem Gerät ungetestet.

## Version 0.1.3-mvp

Geregeltes Scrollen: lange Wischgeste (450 ms plus Haltezeit) ohne Nachschwung, Selbstkalibrierung aus der gemessenen Bildverschiebung (Pixel im Log), Ziel-Überlappung 35 Prozent, Gegenschritt statt Lücke bei verlorener Überlappung, Modus Genau (Mikro-Wischer), Methoden im Tab Debug wählbar, robustere Ausrichtung (längste gemeinsame Teilfolge), Vereinigung gegenüberliegender Anschnitte, Statistik und Statuszeile. Details: PLAN.md Abschnitt 13. Auf dem Gerät ungetestet.

## Version 0.1.2-mvp

Zielmenge (Standard 100 Nachrichten) statt nur N Scrolls, Swipe mit höchstens 70 Prozent Schrittweite, adaptives Warten statt Pausen (Standard maximal schnell, im Tab Debug einstellbar), Abbruch am Chatanfang erst nach 3 Fehlversuchen und Signal, Randnachrichten als angeschnitten markiert und beim nächsten Scroll ersetzt, "Mehr lesen" als gekürzt markiert. Details: PLAN.md Abschnitt 12. Auf dem Gerät ungetestet.

## Version 0.1.1-mvp

Modus "Chat ist schon geöffnet": kein WhatsApp-Start per Intent mehr, stattdessen Countdown (Debug-Tab) oder "Jetzt lesen" in der Benachrichtigung. Such-Navigation wählt nur Treffer im Abschnitt "Chats" (Titelvergleich), klickt per ACTION_CLICK und fällt auf eine Tipp-Geste zurück, danach Kopfzeilenprüfung. Details: PLAN.md Abschnitt 11. Auf dem Gerät ungetestet.
