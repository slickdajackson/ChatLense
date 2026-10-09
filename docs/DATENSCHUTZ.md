# Datenschutzerklärung ChatLens (Entwurf)

Stand: Version 0.3.0. Dies ist ein Entwurf ohne Rechtsberatung. Vor der Veröffentlichung müssen Verantwortlicher, Anschrift und Kontakt eingetragen und der Text rechtlich geprüft werden.

## Verantwortlicher
[Name und Anschrift eintragen]
Kontakt: [E-Mail eintragen]

## Was ChatLens macht
ChatLens liest mit der Bedienungshilfe (Android Accessibility Service) die Texte eines Chats, den du selbst in WhatsApp geöffnet hast. Daraus entstehen Analysen, Antwortvorschläge und ein Gedächtnis mit Profilen deiner Gesprächspartner. ChatLens sendet nie selbst Nachrichten.

## Welche Daten werden verarbeitet
- Chatinhalte, die in WhatsApp auf dem Bildschirm stehen (Namen, Nachrichtentexte, Zeiten), nur während eines Auftrags, den du auslöst.
- Optional Sprachnachrichten aus einem Ordner, den du freigibst, zur Umwandlung in Text auf dem Gerät.
- Optional Screenshots einzelner Bilder für Texterkennung auf dem Gerät.
- Profile (Steckbriefe) je Chat und dein Ich-Profil im Gedächtnis.
- Einstellungen der App.

## Wo bleiben die Daten
- Alles liegt ausschließlich auf deinem Gerät, im privaten App-Speicher, verschlüsselt mit AES-GCM. Der Schlüssel liegt im Android Keystore. Es gibt kein Cloud-Backup (allowBackup ist aus).
- Die Modelle laufen lokal auf dem Gerät. Es werden keine Chatinhalte an einen Server gesendet.
- ChatLens enthält keine Werbung, keine Analyse und keine Weitergabe an Dritte.

## Optional: API-Modus (nur in der Full-Ausführung)
In der Full-Ausführung (nicht in der Play-Ausführung) kannst du einen eigenen API-Server eintragen. Dann gehen Chattexte und optional Bilder an diesen Server. Das ist eine Übermittlung personenbezogener Daten Dritter und braucht eine Rechtsgrundlage. Die Haushaltsausnahme der DSGVO gilt nur bei rein privater Nutzung.

## Berechtigungen
- Bedienungshilfe: zum Lesen des geöffneten Chats und, nach deiner Bestätigung, zum Eintragen eines Entwurfs ins Eingabefeld. ChatLens drückt nie den Senden-Knopf.
- Über anderen Apps einblenden: für den schwebenden Punkt (optional).
- Benachrichtigungen: Anzeige eines laufenden Auftrags mit Abbruch (optional).

## Löschen
Im Tab Gedächtnis kannst du einzelne Profile oder alles löschen. Deinstallieren der App löscht alle Daten.

## Deine Rechte
Du hast Rechte auf Auskunft, Berichtigung, Löschung, Einschränkung, Datenübertragbarkeit und Widerspruch sowie auf Beschwerde bei einer Aufsichtsbehörde. Da ChatLens keine Daten an den Anbieter übermittelt, liegen die Daten bei dir.

## Hinweis zu WhatsApp
Die Nutzungsbedingungen von WhatsApp verbieten automatisierten Zugriff. Eine Kontosperre ist möglich. Du nutzt ChatLens auf eigenes Risiko.
