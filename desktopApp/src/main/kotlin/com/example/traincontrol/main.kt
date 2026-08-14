package com.example.traincontrol

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.russhwolf.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.awt.Color
import java.awt.image.BufferedImage
import java.time.LocalTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.io.File

object AlarmState {
    var message by mutableStateOf("")
    var isVisible by mutableStateOf(value = false)
    var trayTooltip by mutableStateOf("Zug-Anzeige Südtirol")
}

fun main() {
    // 1. Pfad für die Sperrdatei im User-Ordner (temp)
    val lockFile = File(System.getProperty("java.io.tmpdir"), "ZugAnzeigeSuedtirol.lock")
    val raf = RandomAccessFile(lockFile, "rw")

    // 2. Versuche, die Datei zu sperren
    val lock: FileLock? = raf.channel.tryLock()

    if (lock == null) {
        // Wenn lock null ist, läuft bereits eine Instanz
        println("App läuft bereits!")
        return
    }

    application {
        val settings = remember { Settings() }
        val isAppConfigured = remember { settings.getBoolean("is_app_configured", defaultValue = false) }
        var isWindowVisible by remember { mutableStateOf(!isAppConfigured) }

        val stationRepository = remember { StationRepository() }
        val trainService = remember { TrainService() }

    // 1. Initialisierung und Start-Abruf direkt beim App-Start im Hintergrund
    LaunchedEffect(Unit) {
        try {
            TrainState.stations = stationRepository.getStations()
            if (TrainState.stations.isNotEmpty()) {
                val homeName = settings.getString("home_station", "Brixen / Bressanone")
                val workName = settings.getString("work_station", "Bozen / Bolzano")
                TrainState.departureStation = TrainState.stations.find { it.name == homeName } ?: TrainState.stations.first()
                TrainState.targetStation = TrainState.stations.find { it.name == workName } ?: TrainState.stations.first()

                if ((TrainState.departureStation != null) && (TrainState.targetStation != null)) {
                    TrainState.isLoading = true
                    TrainState.trains = trainService.fetchAndParseTrains(
                        TrainState.departureStation!!,
                        TrainState.targetStation!!,
                        settings,
                        TrainState.stations,
                    )
                    TrainState.isLoading = false
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            TrainState.isLoading = false
        }
    }

    // 2. Systray-Tooltip automatisch aktualisieren, wenn sich die Züge ändern
    LaunchedEffect(TrainState.trains) {
        if (TrainState.trains.isNotEmpty()) {
            val nextThree = TrainState.trains.take(3)
            val compactText = nextThree.joinToString(" | ") { train ->
                val status = train.delay.ifBlank { "pünktlich" }
                "${train.time} to ${train.destination} ($status)"
            }
            AlarmState.trayTooltip = if (compactText.length > 120) compactText.take(117) + "..." else compactText
        } else {
            AlarmState.trayTooltip = "Zug-Anzeige Südtirol"
        }
    }

    // 3. Hintergrund-Aktualisierung im 5-Minuten-Takt (für den Systray-Tooltip)
    LaunchedEffect(TrainState.departureStation, TrainState.targetStation) {
        if ((TrainState.departureStation != null) && (TrainState.targetStation != null)) {
            while (true) {
                delay(5.minutes)
                try {
                    TrainState.trains = trainService.fetchAndParseTrains(
                        TrainState.departureStation!!,
                        TrainState.targetStation!!,
                        settings,
                        TrainState.stations,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // 4. Hintergrund-Timer für den Alarm zur eingestellten Uhrzeit (angepasst für Wochentage)
    LaunchedEffect(settings.getBoolean("timer_active", defaultValue = false), TrainState.departureStation, TrainState.targetStation) {
        while (true) {
            // Master-Schalter prüfen
            val isTimerActive = settings.getBoolean("timer_active", defaultValue = false)

            if (isTimerActive) {
                val now = LocalTime.now()
                val today = java.time.LocalDate.now().dayOfWeek

                // Lade die Einstellung für genau HEUTE aus den Settings
                val isTodayActive = settings.getBoolean("active_${today.name}", defaultValue = false)
                val timeForToday = settings.getString("time_${today.name}", "16:40")

                // Alarm nur auslösen, wenn der heutige Tag aktiv ist und die Uhrzeit stimmt
                if (isTodayActive && (String.format("%02d:%02d", now.hour, now.minute) == timeForToday.trim()) && (now.second < 5)) {
                    if ((TrainState.departureStation != null) && (TrainState.targetStation != null)) {
                        try {
                            TrainState.trains = trainService.fetchAndParseTrains(
                                TrainState.departureStation!!,
                                TrainState.targetStation!!,
                                settings,
                                TrainState.stations,
                            )

                            val firstTrain = TrainState.trains.getOrNull(0)
                            val secondTrain = TrainState.trains.getOrNull(1)

                            val hasIssue = { train: TrainInfo ->
                                val efaText = train.delay.trim().lowercase()
                                val efaIssue = train.hasDelay || (efaText.isNotBlank() && efaText != "0" && efaText != "pünktlich" && efaText != "in orario")
                                
                                val rfiText = train.rfiStatus?.trim()?.lowercase() ?: ""
                                val rfiIssue = rfiText == "verspätung" || rfiText == "entfällt"
                                
                                efaIssue || rfiIssue
                            }

                            // Hilfsfunktion NUR für das Popup (fügt " Verspätung" an)
                            val getDelayText = { train: TrainInfo ->
                                val efaText = train.delay.trim().lowercase()
                                val efaIssue = train.hasDelay || (efaText.isNotBlank() && efaText != "0" && efaText != "pünktlich" && efaText != "in orario")
                                
                                if (efaIssue) {
                                    val text = train.delay
                                    if (text.any { it.isDigit() } && !text.contains("Verspätung", ignoreCase = true)) {
                                        "$text Verspätung"
                                    } else {
                                        text
                                    }
                                } else {
                                    val rfiText = train.rfiStatus?.trim()?.lowercase() ?: ""
                                    if (rfiText == "verspätung" || rfiText == "entfällt") {
                                        if (train.rfiDelay?.isNotBlank() == true) "${train.rfiDelay} (RFI)" else "${train.rfiStatus} (RFI)"
                                    } else {
                                        train.delay
                                    }
                                }
                            }

                            if (firstTrain != null) {
                                var triggerPopup = false
                                val messageBuilder = java.lang.StringBuilder()

                                // 1. Zug prüfen (für das Popup)
                                if (hasIssue(firstTrain)) {
                                    messageBuilder.append("• ${firstTrain.time} Uhr (Nr. ${firstTrain.categoryNumber}): ${getDelayText(firstTrain)}\n")
                                    triggerPopup = true
                                }

                                // 2. Zug prüfen (innerhalb von 15 Minuten)
                                if (secondTrain != null) {
                                    val parts1 = firstTrain.time.split(":")
                                    val parts2 = secondTrain.time.split(":")

                                    if ((parts1.size == 2) && (parts2.size == 2)) {
                                        val h1 = parts1[0].toIntOrNull() ?: 0
                                        val m1 = parts1[1].toIntOrNull() ?: 0
                                        val h2 = parts2[0].toIntOrNull() ?: 0
                                        val m2 = parts2[1].toIntOrNull() ?: 0

                                        val totalMins1 = h1 * 60 + m1
                                        var totalMins2 = h2 * 60 + m2

                                        if (totalMins2 < totalMins1) totalMins2 += 1440

                                        val diff = totalMins2 - totalMins1

                                        if (diff <= 15 && hasIssue(secondTrain)) {
                                            messageBuilder.append("• ${secondTrain.time} Uhr (Nr. ${secondTrain.categoryNumber}): ${getDelayText(secondTrain)}\n")
                                            triggerPopup = true
                                        }
                                    }
                                }

                                if (triggerPopup) {
                                    trainService.playDoubleBeep()

                                    // Windows-Toast-Benachrichtigung: Nutzt EFA-Delay oder RFI-Delay
                                    val fullMsg = TrainState.trains.joinToString("\n") {
                                        val efaText = it.delay.trim().lowercase()
                                        val efaIssue = it.hasDelay || (efaText.isNotBlank() && efaText != "0" && efaText != "pünktlich" && efaText != "in orario")
                                        
                                        val status = if (efaIssue) {
                                            it.delay
                                        } else {
                                            val rfiText = it.rfiStatus?.trim()?.lowercase() ?: ""
                                            if (rfiText == "verspätung" || rfiText == "entfällt") {
                                                if (it.rfiDelay?.isNotBlank() == true) "${it.rfiDelay} (RFI)" else "${it.rfiStatus} (RFI)"
                                            } else {
                                                it.delay.ifBlank { "pünktlich" }
                                            }
                                        }
                                        "${it.time} | ${it.destination} | $status"
                                    }
                                    trainService.showNotification("Fahrplan-Meldung! ⚠️ ", fullMsg)

                                    AlarmState.message = messageBuilder.toString().trimEnd()
                                    AlarmState.isVisible = true
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    delay(1.minutes)
                }
            }
            delay(1.seconds)
        }
    }

    val appIcon = remember {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(197, 48, 48); g.fillRoundRect(0, 0, 64, 64, 12, 12)
            g.color = Color.WHITE; g.font = java.awt.Font("Arial", java.awt.Font.BOLD, 40)
            g.drawString("Tc", 8, 48); g.dispose()
        }
        androidx.compose.ui.graphics.painter.BitmapPainter(image.toComposeImageBitmap())
    }

    Tray(
        icon = appIcon,
        tooltip = AlarmState.trayTooltip,
        onAction = { isWindowVisible = true },
        menu = {
            Item("Öffnen") { isWindowVisible = true }
            Separator()
            Item("Beenden", onClick = ::exitApplication)
        },
    )

    // Fenster 1: Die Haupt-App
    Window(
        onCloseRequest = { isWindowVisible = false },
        title = "Zug-Anzeige Südtirol",
        icon = appIcon,
        state = rememberWindowState(
            placement = if (!isAppConfigured) WindowPlacement.Maximized else WindowPlacement.Floating,
            width = 900.dp,
            height = 750.dp
        ),
        visible = isWindowVisible,
    ) {
        App()
    }

    // Fenster 2: Das moderne Alert-Fenster
    if (AlarmState.isVisible) {
        Window(
            onCloseRequest = { AlarmState.isVisible = false },
            title = "ACHTUNG: Zugmeldung!",
            icon = appIcon,
            state = rememberWindowState(width = 600.dp, height = 230.dp),
            alwaysOnTop = true,
            undecorated = false,
        ) {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF333333),
                    surface = androidx.compose.ui.graphics.Color.White,
                    background = androidx.compose.ui.graphics.Color.White,
                    surfaceVariant = androidx.compose.ui.graphics.Color.White,
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "Meldung für Züge nach ${TrainState.targetStation?.name}:",
                                style = MaterialTheme.typography.titleLarge,
                                color = androidx.compose.ui.graphics.Color(0xFFC53030)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = AlarmState.message,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = { AlarmState.isVisible = false },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = androidx.compose.ui.graphics.Color(0xFF333333)
                                )
                            ) {
                                Text("Ok")
                            }
                        }
                    }
                }
            }
        }
    }

    // Beim Beenden Sperre freigeben
    DisposableEffect(Unit) {
        onDispose {
            try {
                lock.release()
                raf.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    }
}
