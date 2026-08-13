This is a Kotlin Multiplatform project targeting Desktop (JVM).

# Zugmonitoring für Südtiroler Pendler
Eine einfache Desktop-Anwendung, entwickelt mit **Kotlin** und **Jetpack Compose Desktop**, zur komfortablen Überwachung von Zugverbindungen, Abfahrtszeiten und Verspätungen im öffentlichen Nahverkehr (Bahnverkehr). Die App holt die Zugdaten von der Südtirol Mobil Webseite und macht einen Quervergleich mit der Webseite der RFI Anzeigetafeln.  

## Über das "Projekt"
Gedacht ist die App für Südtiroler Bahn-Pendler, die täglich die gleichen Strecken zu bestimmten Zeiten fahren (z. B. in der Früh zur Arbeit und am Abend zurück). Die Anwendung prüft automatisch zu festgelegten Zeiten, ob z.B. die relevanten Züge in Richtung Heimat pünktlich unterwegs sind, und nur im Falle von Unpünktlichkeit oder Ausfall erscheint eine Meldung auf dem Bildschirm. Ansonsten läuft die App absolut silent im Hintergrund in der Systemtray und öffnet sich nur durch Doppelklick auf das Symbol für etwaige Konfiguration oder spontanes Echtzeitmonitoring.

Also nichts Besonderes, aber für meinen Gebrauch reicht das vollkommen aus. Wer möchte kann gerne selbst die App ändern, verbessern und damit machen was er will und gerne auch etwaige Verbesserungen allen zugänglich machen.

## Hauptfunktionen
- Hintergrundbetrieb: Die App läuft still und leise minimiert im System-Tray (neben der Uhr).
- Automatischer Check: Zu frei konfigurierbaren Tagen und Zeiten wird geprüft, ob der nächstmögliche Zug oder ein darauffolgender Zug, welcher innerhalb der nächsten 15 Minuten in die selbe Richtung fährt, Verspätung hat oder ausfällt und gibt ein Popup samt Alarmsignal (Beep) aus.
- Schnellübersicht per Hover: Beim Überfahren des Tray-Symbols mit der Maus werden die nächsten 3 Züge inklusive ihrer Pünktlichkeit bzw. Verspätung angezeigt.
- Einfache Konfiguration: Per Doppelklick auf das App-Icon öffnen sich die Einstellungen, in denen Abfahrtsbahnhof, Zielbahnhof und die Prüfzeiten angepasst werden können.
- Autostart: Aktivierung durch Checkbox um die App automatisch bei jedem Start im Hintergrund ausführen zu lassen.

## Datenquelle
Die App zieht die Daten von den Webseiten von [Südtirol Mobil](https://www.suedtirolmobil.info), macht den Quervergleich über die Webseite der RFI Monitore - Anzeigetafeln z.B. für Brixen [RFI Monitor](https://iechub.rfi.it/ArriviPartenze/arrivalsdepartures/Monitor?placeId=738&arrivals=false). [viaggiatreno.it](http://www.viaggiatreno.it/infomobilita/index.jsp) konnte leider nicht verwendet werden, da die Sad-Züge fehlen.

## Installation & Start

1. Klone das Repository:
   ```bash
   git clone https://github.com/ag7319-collab/TrainControlSTmobil.git
2. Download der Portable Version (selbstextrahierendes Archiv) oder der installierbaren Version (MSI-Installer) hier:  
   [TrainControlSTmobil Releases](https://github.com/ag7319-collab/TrainControlSTmobil/releases/)  
   *Die Portable Version entpackt sich in den Ordner TrainControlSTmobil unter Eigene Dateien und führt die App beim ersten Start aus. Es bedarf im Gegensatz zum MSI-Installer keiner Administratorenrechte.*
## Tech Stack

* **Sprache:** Kotlin
* **UI-Framework:** Jetpack Compose for Desktop
* **Persistenz:** Multiplatform Settings (`com.russhwolf:settings`)
* **Asynchronität:** Kotlin Coroutines  
    
Warum Kotlin? Weil ich das "Projekt" ursprünglich als Android-App gestartet hatte und durch Kotlin die Umwandlung in eine Desktop-App recht einfach war. Thats it.

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…
