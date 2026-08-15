package com.example.traincontrol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import com.russhwolf.settings.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.io.File
import java.time.DayOfWeek

@Composable
fun App() {
    val trainService = remember { TrainService() }
    val settings = remember { Settings() }

    var isTimerActive by remember { mutableStateOf(settings.getBoolean("timer_active", defaultValue = false)) }
    var runOnStartup by remember { mutableStateOf(settings.getBoolean("run_on_startup", defaultValue = false)) }

    // Selbstheilung für Portable Version: Beim Start Autostart-Verknüpfung aktualisieren, falls aktiv
    LaunchedEffect(Unit) {
        if (runOnStartup) {
            setWindowsAutostart(enable = true)
        }
    }

    var showSettingsDialog by remember { mutableStateOf(value = false) }
    var showWeeklyDialog by remember { mutableStateOf(value = false) }
    var refreshTrigger by remember { mutableStateOf(0) }

    // Zentrale Suche: Reagiert auf Bahnhof-Änderungen und den Refresh-Trigger.
    // LaunchedEffect bricht automatisch die vorherige Suche ab, wenn sich ein Key ändert.
    LaunchedEffect(TrainState.departureStation, TrainState.targetStation, refreshTrigger) {
        val from = TrainState.departureStation
        val to = TrainState.targetStation

        if ((from != null) && (to != null) && (from.name != to.name)) {
            // Debounce: Wir warten kurz, falls der Nutzer gerade beide Bahnhöfe 
            // schnell hintereinander umstellt (verhindert Race Conditions).
            // Debounce: Wir warten kurz, falls der Nutzer gerade beide Bahnhöfe 
            // schnell hintereinander umstellt (verhindert Race Conditions).
            delay(400)
            
            try {
                TrainState.isLoading = true
                TrainState.trains = trainService.fetchAndParseTrains(from, to, settings, TrainState.stations)
            } catch (e: CancellationException) {
                // Das ist bei LaunchedEffect normal, wenn sich Keys ändern.
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                TrainState.isLoading = false
            }
        } else {
            TrainState.trains = emptyList()
        }
    }

    val southTyrolRed = Color(0xFFC53030)
    val darkGray = Color(0xFF333333)
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = southTyrolRed,
            onPrimary = Color.White,
            surface = Color.White,
            background = Color.White,
            surfaceVariant = Color.White,
            secondaryContainer = Color(0xFFF5F5F5),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Zug-Anzeige Südtirol",
                        style = MaterialTheme.typography.headlineMedium,
                        color = southTyrolRed,
                    )
                    OutlinedButton(onClick = { showSettingsDialog = true }) {
                        Text("⚙️ Kategorien")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Abfahrts-Dropdown
                StationDropdown(
                    stations = TrainState.stations,
                    selected = TrainState.departureStation,
                ) { selected ->
                    TrainState.departureStation = selected
                    settings.putString("home_station", selected.name)
                    settings.putBoolean("is_app_configured", value = true)

                    // Wenn Ziel = Abfahrt, setze Ziel zurück
                    if (TrainState.targetStation?.name == selected.name) {
                        TrainState.targetStation = null
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Ziel-Dropdown
                StationDropdown(
                    stations = TrainState.stations,
                    selected = TrainState.targetStation
                ) { selected ->
                    TrainState.targetStation = selected
                    settings.putString("work_station", selected.name)
                    settings.putBoolean("is_app_configured", value = true)

                    // Wenn Abfahrt = Ziel, setze Abfahrt zurück
                    if (TrainState.departureStation?.name == selected.name) {
                        TrainState.departureStation = null
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Kombinierte Row: Autostart + Monitoring + Wochentage
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 1. Checkbox: Autostart (zuerst)
                        Checkbox(
                            checked = runOnStartup,
                            onCheckedChange = { isChecked ->
                                runOnStartup = isChecked
                                settings.putBoolean("run_on_startup", isChecked)
                                settings.putBoolean("is_app_configured", value = true)
                                setWindowsAutostart(isChecked)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = southTyrolRed)
                        )
                        Text("Autostart")

                        Spacer(modifier = Modifier.width(16.dp))

                        // 2. Checkbox: Zugmonitoring (danach)
                        Checkbox(
                            checked = isTimerActive,
                            onCheckedChange = {
                                isTimerActive = it
                                settings.putBoolean("timer_active", it)
                                settings.putBoolean("is_app_configured", value = true)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = southTyrolRed)
                        )
                        Text("Geplantes automatisches Zugmonitoring")
                    }

                    // Button ganz rechts
                    OutlinedButton(onClick = { showWeeklyDialog = true }) {
                        Text("📅 Wochentage einstellen")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { refreshTrigger++ },
                    enabled = (!TrainState.isLoading) && (TrainState.departureStation != null) && (TrainState.targetStation != null) && (TrainState.departureStation?.name != TrainState.targetStation?.name),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (TrainState.isLoading) "Suche läuft..." else "Züge jetzt suchen")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(modifier = Modifier.fillMaxSize()) {
                    if (TrainState.trains.isNotEmpty()) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(TrainState.trains) { train ->
                                TrainItem(train)
                                HorizontalDivider()
                            }
                        }
                    }

                    if (TrainState.isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.White.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    if (showWeeklyDialog) {
        WeeklyTimerDialog(settings = settings, southTyrolRed = southTyrolRed, darkGray = darkGray) {
            showWeeklyDialog = false
        }
    }

    if (showSettingsDialog) {
        var catReg by remember { mutableStateOf(value = settings.getBoolean("cat_reg", defaultValue = true)) }
        var catRv by remember { mutableStateOf(value = settings.getBoolean("cat_rv", defaultValue = true)) }
        var catBus by remember { mutableStateOf(value = settings.getBoolean("cat_bus", defaultValue = false)) }
        var catTrenord by remember { mutableStateOf(value = settings.getBoolean("cat_tn_rj", defaultValue = false)) }
        var catFreccia by remember { mutableStateOf(value = settings.getBoolean("cat_fv_freccia", defaultValue = false)) }
        var catItalo by remember { mutableStateOf(value = settings.getBoolean("cat_fv_italo", defaultValue = false)) }
        var catIc by remember { mutableStateOf(value = settings.getBoolean("cat_fv_ic", defaultValue = false)) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = {
                Text(
                    text = "Zugkategorien filtern",
                    color = southTyrolRed
                )
            },
            containerColor = Color.White,
            text = {
                Column {
                    CategoryCheckbox("Regionalzüge", catReg) { catReg = it }
                    CategoryCheckbox("Regionalexpress", catRv) { catRv = it }
                    CategoryCheckbox("Ersatzbus", catBus) { catBus = it }
                    CategoryCheckbox("Eurocity / Railjet", catTrenord) { catTrenord = it }
                    CategoryCheckbox("Frecciarossa", catFreccia) { catFreccia = it }
                    CategoryCheckbox("Italo", catItalo) { catItalo = it }
                    CategoryCheckbox("Intercity", catIc) { catIc = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        settings.putBoolean("cat_reg", catReg)
                        settings.putBoolean("cat_rv", catRv)
                        settings.putBoolean("cat_bus", catBus)
                        settings.putBoolean("cat_tn_rj", catTrenord)
                        settings.putBoolean("cat_fv_freccia", catFreccia)
                        settings.putBoolean("cat_fv_italo", catItalo)
                        settings.putBoolean("cat_fv_ic", catIc)
                        settings.putBoolean("is_app_configured", value = true)
                        showSettingsDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = darkGray)
                ) {
                    Text("Speichern")
                }
            }
        )
    }
}

@Composable
fun WeeklyTimerDialog(settings: Settings, southTyrolRed: Color, darkGray: Color, onDismiss: () -> Unit) {
    val days = listOf(
        DayOfWeek.MONDAY to "Montag",
        DayOfWeek.TUESDAY to "Dienstag",
        DayOfWeek.WEDNESDAY to "Mittwoch",
        DayOfWeek.THURSDAY to "Donnerstag",
        DayOfWeek.FRIDAY to "Freitag",
        DayOfWeek.SATURDAY to "Samstag",
        DayOfWeek.SUNDAY to "Sonntag"
    )

    var editingDay by remember { mutableStateOf<DayOfWeek?>(null) }

    LaunchedEffect(Unit) {
        if (!settings.getBoolean("initialized_days_v2", false)) {
            days.forEach { (dayOfWeek, _) ->
                val defaultActive = (dayOfWeek != DayOfWeek.SATURDAY) && (dayOfWeek != DayOfWeek.SUNDAY)
                settings.putBoolean("active_${dayOfWeek.name}", defaultActive)
            }
            settings.putBoolean("initialized_days_v2", value = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Alarm-Zeiten pro Wochentag",
                color = southTyrolRed
            )
        },
        containerColor = Color.White,
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                days.forEach { (dayOfWeek, dayName) ->
                    val defaultActive = (dayOfWeek != DayOfWeek.SATURDAY) && (dayOfWeek != DayOfWeek.SUNDAY)
                    var isActive by remember { mutableStateOf(settings.getBoolean("active_${dayOfWeek.name}", defaultActive)) }
                    var time by remember { mutableStateOf(settings.getString("time_${dayOfWeek.name}", "16:40")) }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isActive,
                                onCheckedChange = {
                                    isActive = it
                                    settings.putBoolean("active_${dayOfWeek.name}", it)
                                },
                                colors = CheckboxDefaults.colors(checkedColor = southTyrolRed)
                            )
                            Text(dayName, modifier = Modifier.width(90.dp))
                        }

                        OutlinedButton(
                            onClick = { editingDay = dayOfWeek },
                            enabled = isActive
                        ) {
                            Text(time)
                        }
                    }

                    if (editingDay == dayOfWeek) {
                        TimePickerDialog(
                            initialTime = time,
                            onDismiss = { editingDay = null }
                        ) { newTime ->
                            time = newTime
                            settings.putString("time_${dayOfWeek.name}", newTime)
                            editingDay = null
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    settings.putBoolean("is_app_configured", value = true)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = darkGray)
            ) {
                Text("Fertig")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initialTime: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val parts = initialTime.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: 16
    val m = parts.getOrNull(1)?.toIntOrNull() ?: 40
    val timePickerState = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
    val darkGray = Color(0xFF333333)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Uhrzeit wählen",
                color = Color(0xFFC53030)
            )
        },
        containerColor = Color.White,
        text = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimePicker(state = timePickerState)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val formattedH = timePickerState.hour.toString().padStart(2, '0')
                    val formattedM = timePickerState.minute.toString().padStart(2, '0')
                    onConfirm("$formattedH:$formattedM")
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = darkGray)
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = darkGray)
            ) {
                Text("Abbrechen")
            }
        }
    )
}

@Composable
fun CategoryCheckbox(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFC53030))
        )
        Text(label)
    }
}

@Composable
fun StationDropdown(
    stations: List<StationData>,
    selected: StationData?,
    onSelected: (StationData) -> Unit
) {
    var expanded by remember { mutableStateOf(value = false) }
    val filteredStations = remember(stations) {
        stations
            .asSequence()
            .filter { (!it.placeId.startsWith("9900")) && (it.lat >= 46.0) && it.isSelectable }
            .sortedBy { it.name }
            .toList()
    }

    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.name ?: "Bahnhof wählen...")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 400.dp),
            containerColor = Color(0xFFF5F5F5) // Helles Grau (entspricht secondaryContainer/Hover)
        ) {
            filteredStations.forEach { station ->
                DropdownMenuItem(
                    text = { Text(station.name) },
                    onClick = { onSelected(station); expanded = false }
                )
            }
        }
    }
}

@Composable
fun TrainItem(train: TrainInfo) {
    val lightGray = Color(0xFF999999)
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Links: Zug-Kategorie und Nummer
            Column(modifier = Modifier.weight(1f)) {
                Text(train.categoryNumber, style = MaterialTheme.typography.titleMedium)
                
                // Der Endbahnhof des Zuges (wo die Linie endet)
                Text("nach ${train.lineTerminal ?: train.destination}", style = MaterialTheme.typography.bodyMedium)
                
                // Dein persönliches Ziel, falls der Zug noch weiterfährt
                if (train.lineTerminal != null && 
                    !train.lineTerminal.equals(train.destination, ignoreCase = true) &&
                    !train.lineTerminal.contains(train.destination.split("/").first().trim(), ignoreCase = true)) {
                    Text("Ziel ${train.destination}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFC53030))
                }
                
                Text(if (train.isBus) "BUS" else "Gleis: ${train.platform}", style = MaterialTheme.typography.bodySmall)
            }

            // Mitte: RFI Monitor (Gegencheck)
            if (train.rfiStatus != null || train.rfiDelay != null) {
                val rfiHasIssue = train.rfiStatus == "Verspätung" || train.rfiStatus == "entfällt"
                val rfiColor = if (rfiHasIssue) MaterialTheme.colorScheme.error else lightGray
                
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = train.rfiDelay ?: "0+",
                        style = MaterialTheme.typography.bodySmall,
                        color = rfiColor
                    )
                    Text(
                        text = "RFI-Monitor",
                        style = MaterialTheme.typography.labelSmall,
                        color = lightGray
                    )
                    Text(
                        text = train.rfiStatus ?: "pünktlich",
                        style = MaterialTheme.typography.bodySmall,
                        color = rfiColor
                    )
                }
            } else {
                // Spacer um die Mitte leer zu halten falls kein RFI Match
                Box(modifier = Modifier.weight(1f))
            }

            // Rechts: Uhrzeit und EFA-Status
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Text(train.time, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = train.delay,
                    color = if (train.hasDelay) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

fun setWindowsAutostart(enable: Boolean) {
    // Führt die Arbeit im Hintergrund aus, damit das UI nicht einfriert
    Thread {
        try {
            val appData = System.getenv("APPDATA") ?: return@Thread
            val startupFolder = "$appData\\Microsoft\\Windows\\Start Menu\\Programs\\Startup"
            val shortcutFile = File(startupFolder, "ZugAnzeigeSuedtirol.lnk")

            val appPath = System.getProperty("jpackage.app-path")
                ?: File(System.getProperty("user.dir"), "TrainControlSTmobil.exe").absolutePath

            if (enable) {
                val psScript = $$"""
                    $WshShell = New-Object -comObject WScript.Shell;
                    $Shortcut = $WshShell.CreateShortcut('$${shortcutFile.absolutePath}');
                    $Shortcut.TargetPath = '$$appPath';
                    $Shortcut.Save();
                """.trimIndent()

                val process = ProcessBuilder("powershell.exe", "-Command", psScript).start()
                process.waitFor() // Blockiert jetzt nur den unsichtbaren Hintergrund-Thread, nicht mehr die App!
            } else {
                if (shortcutFile.exists()) {
                    shortcutFile.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.start()
}