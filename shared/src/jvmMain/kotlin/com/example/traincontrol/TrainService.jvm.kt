@file:OptIn(ExperimentalMultiplatform::class)
package com.example.traincontrol

import kotlin.ExperimentalMultiplatform
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import java.awt.Color
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import kotlin.time.Duration.Companion.seconds

actual class TrainService actual constructor() {

    private val httpClient = HttpClient(CIO)

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    actual suspend fun fetchAndParseTrains(
        fromStation: StationData,
        targetStation: StationData,
        settings: Settings,
        @Suppress("UNUSED_PARAMETER") allStations: List<StationData>,
    ): List<TrainInfo> {
        val rawTrainList = mutableListOf<TrainInfo>()
        val limit = 6

        val allowReg = settings.getBoolean("cat_reg", defaultValue = true)
        val allowRv = settings.getBoolean("cat_rv", defaultValue = true)
        // Die Bus-Checkbox aus den Settings wird hier ignoriert, da sie nur für RFI-Ersatzbusse gedacht ist.
        // Für Südtirol Mobil (EFA) werden Busse grundsätzlich ausgefiltert.
        val allowTrenord = settings.getBoolean("cat_tn_rj", defaultValue = false)
        val allowFreccia = settings.getBoolean("cat_fv_freccia", defaultValue = false)
        val allowItalo = settings.getBoolean("cat_fv_italo", defaultValue = false)
        val allowIC = settings.getBoolean("cat_fv_ic", defaultValue = false)

        val now = java.time.LocalDateTime.now()
        val dateStr = now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
        val timeStr = now.format(java.time.format.DateTimeFormatter.ofPattern("HHmm"))

        for (attempt in 1..2) {
            rawTrainList.clear()
            try {
                val efaFromId = fromStation.efaId ?: resolveEfaId(fromStation.name)
                val efaToId = targetStation.efaId ?: resolveEfaId(targetStation.name)

                // ptOptionsActive=1 + inclMOT_0=1 (Zug) + inclMOT_1=1 (S-Bahn/Regionalzug) 
                // Wir schalten Busse (MOT 5, 6, 7) explizit aus.
                val url = "https://efa.sta.bz.it/web/XML_TRIP_REQUEST2?sessionID=0&requestID=0&name_origin=$efaFromId&type_origin=stop&name_destination=$efaToId&type_destination=stop&itdDate=$dateStr&itdTime=$timeStr&useRealtime=1&outputFormat=JSON&language=de&odvMacro=true&ptOptionsActive=1&itOptionsActive=1&inclMOT_0=1&inclMOT_1=1&inclMOT_2=1&inclMOT_3=1&inclMOT_4=0&inclMOT_5=0&inclMOT_6=0&inclMOT_7=0&inclMOT_8=0&inclMOT_9=0&inclMOT_10=0&inclMOT_11=0"

                val responseStr: String = withContext(Dispatchers.IO) {
                    val resp = httpClient.get(url)
                    println("DEBUG: URL=$url")
                    println("DEBUG: Status=${resp.status}")
                    resp.bodyAsText()
                }

                println("DEBUG: Response length=${responseStr.length}")
                if (responseStr.length < 500) println("DEBUG: Response=$responseStr")

                val root = json.parseToJsonElement(responseStr) as? JsonObject ?: continue
                val tripResponse = root["tripResponse"] as? JsonObject

                val trips = getAsList(root, "journey")
                    .ifEmpty { getAsList(root, "journeys") }
                    .ifEmpty { getAsList(root, "trip") }
                    .ifEmpty { getAsList(root, "trips") }
                    .ifEmpty { getAsList(tripResponse ?: root, "journey") }
                    .ifEmpty { getAsList(tripResponse ?: root, "trip") }
                    .ifEmpty { getAsList(tripResponse ?: root, "tripList") }

                println("DEBUG: Found ${trips.size} trips")
                if (trips.isEmpty()) {
                    println("DEBUG: Raw root keys: ${root.keys}")
                    if (tripResponse != null) println("DEBUG: Raw tripResponse keys: ${tripResponse.keys}")
                }

                for (trip in trips) {
                    val legs = getAsList(trip, "leg")
                        .ifEmpty { getAsList(trip, "legs") }
                        .ifEmpty { getAsList(trip, "legList") }

                    // NEU: Wir prüfen den GESAMTEN Trip. Wenn IRGENDEIN Bein ein Bus ist, 
                    // und Busse nicht erlaubt sind, verwerfen wir die gesamte Verbindung.
                    var tripBanned = false
                    for (legObj in legs) {
                        val transp = (legObj["transportation"] as? JsonObject) ?: (legObj["mode"] as? JsonObject)
                        val tName = (transp?.get("name") as? JsonPrimitive)?.content ?: ""
                        val isWalk = tName.contains("Fußweg", ignoreCase = true) || (legObj["isWalk"] as? JsonPrimitive)?.booleanOrNull == true
                        if (isWalk) continue

                        val upper = tName.uppercase()
                        val isBus = upper.contains("BUS") || upper.contains("SAD") || upper.contains("SASA") || upper.contains("LINIE")
                        val isTrenordOrRJ = upper.contains("RJ") || upper.contains("RAILJET") || upper.contains("EC")
                        val isFreccia = upper.contains("FRECCIA") || upper.contains("FR ")
                        val isItalo = upper.contains("ITALO")
                        val isIC = upper.contains("INTERCITY") || upper.contains("IC ")
                        val isRv = upper.contains("RV") || upper.contains("REGIONALE VELOCE")
                        val isReg = !isBus && !isRv && !isTrenordOrRJ && !isFreccia && !isItalo && !isIC

                        if (isBus) { tripBanned = true; break } // Busse von EFA IMMER verbieten
                        if (isTrenordOrRJ && !allowTrenord) { tripBanned = true; break }
                        if (isFreccia && !allowFreccia) { tripBanned = true; break }
                        if (isItalo && !allowItalo) { tripBanned = true; break }
                        if (isIC && !allowIC) { tripBanned = true; break }
                        if (isRv && !allowRv) { tripBanned = true; break }
                        if (isReg && !allowReg) { tripBanned = true; break }
                    }
                    if (tripBanned) continue

                    val mainLeg = legs.firstOrNull { legObj ->
                        val transp = (legObj["transportation"] as? JsonObject) ?: (legObj["mode"] as? JsonObject)
                        val tName = (transp?.get("name") as? JsonPrimitive)?.content ?: ""
                        val isWalk = tName.contains("Fußweg", ignoreCase = true) || (legObj["isWalk"] as? JsonPrimitive)?.booleanOrNull == true
                        !isWalk
                    } ?: continue

                    val points = getAsList(mainLeg, "point").ifEmpty { getAsList(mainLeg, "points") }

                    val originNode = (mainLeg["origin"] as? JsonObject)
                        ?: points.firstOrNull { ((it["usage"] as? JsonPrimitive)?.content == "departure") }
                        ?: points.firstOrNull()

                    val transpNode = (mainLeg["transportation"] as? JsonObject) ?: (mainLeg["mode"] as? JsonObject)

                    if (originNode == null || transpNode == null) continue

                    val transpName = (transpNode["name"] as? JsonPrimitive)?.content
                        ?: (transpNode["disassembledName"] as? JsonPrimitive)?.content
                        ?: ((transpNode["product"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                        ?: "Zug"

                    val finalDest = ((transpNode["destination"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                        ?: (transpNode["destination"] as? JsonPrimitive)?.content
                        ?: targetStation.name

                    val upperCat = transpName.uppercase()
                    val isBus = upperCat.contains("BUS") || upperCat.contains("SAD") || upperCat.contains("SASA") || upperCat.contains("LINIE")

                    // FILTER: Nur Züge des heutigen Tages anzeigen
                    val planDate = extractDate(originNode, listOf("itdTime", "dateTime", "departureTimePlanned", "date"))
                    if (planDate != null && planDate != dateStr) continue

                    // DIE KORREKTUR: Sucht nun gezielt nach itdTime, was EFA für Trips verwendet
                    val planTime = extractTime(originNode, listOf("itdTime", "dateTime", "departureTimePlanned", "time"))
                    val realTime = extractTime(originNode, listOf("itdRTTime", "realDateTime", "departureTimeEstimated", "rtTime")) ?: planTime

                    if (planTime == null) {
                        println("DEBUG: No planTime found in originNode keys: ${originNode.keys}")
                        continue
                    }

                    val platform = (originNode["platformName"] as? JsonPrimitive)?.content ?: "-"

                    var formattedDelay = "pünktlich"
                    var hasDelay = false

                    if (realTime != null && realTime != planTime) {
                        val pH = planTime.substringBefore(":").toIntOrNull() ?: 0
                        val pM = planTime.substringAfter(":").toIntOrNull() ?: 0
                        val rH = realTime.substringBefore(":").toIntOrNull() ?: pH
                        val rM = realTime.substringAfter(":").toIntOrNull() ?: pM

                        val pTotal = pH * 60 + pM
                        var rTotal = rH * 60 + rM
                        if (rTotal < pTotal && (pTotal - rTotal) > 720) rTotal += 1440

                        val delayMins = rTotal - pTotal
                        if (delayMins > 0) {
                            hasDelay = true
                            formattedDelay = "+$delayMins Min."
                        }
                    }

                    val isCancelledAttr = (originNode["isCancelled"] as? JsonPrimitive)?.content
                    val isCancelledBool = (originNode["isCancelled"] as? JsonPrimitive)?.booleanOrNull
                    if (isCancelledAttr == "1" || isCancelledBool == true) {
                        hasDelay = true
                        formattedDelay = "entfällt"
                    }

                    if (rawTrainList.any { it.categoryNumber == transpName && it.time == planTime }) {
                        continue
                    }

                    rawTrainList.add(
                        TrainInfo(
                            categoryNumber = transpName,
                            destination = finalDest,
                            time = planTime,
                            delay = formattedDelay,
                            platform = platform,
                            hasDelay = hasDelay,
                            isBus = isBus,
                            stopsAtTarget = true,
                        )
                    )

                    if (rawTrainList.size >= limit) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
            }

            if (rawTrainList.isNotEmpty()) break
            if (attempt == 1) delay(1.seconds)
        }

        return rawTrainList.sortedBy { it.time }
    }

    private fun getAsList(node: JsonObject, key: String): List<JsonObject> {
        val element = node[key] ?: node["${key}s"] ?: return emptyList()
        return when (element) {
            is JsonArray -> element.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
    }

    // DIE KORREKTUR: Die Uhrzeit-Suche geht jetzt alle möglichen EFA-Feldnamen durch
    private fun extractDate(node: JsonObject, keys: List<String>): String? {
        for (key in keys) {
            val element = node[key]
            if (element is JsonObject) {
                val y = (element["year"] as? JsonPrimitive)?.content
                val m = (element["month"] as? JsonPrimitive)?.content?.padStart(2, '0')
                val d = (element["day"] as? JsonPrimitive)?.content?.padStart(2, '0')
                if (y != null && m != null && d != null) return "$y$m$d"

                val dateStr = (element["date"] as? JsonPrimitive)?.content
                if (dateStr != null) {
                    // Format "dd.MM.yyyy" -> "yyyyMMdd"
                    val parts = dateStr.split(".")
                    if (parts.size == 3) return "${parts[2]}${parts[1]}${parts[0]}"
                }
            } else if (element is JsonPrimitive) {
                val dateStr = element.content
                if (dateStr.contains(".")) {
                    val parts = dateStr.split(".")
                    if (parts.size == 3) return "${parts[2]}${parts[1]}${parts[0]}"
                }
            }
        }
        return null
    }

    private fun extractTime(node: JsonObject, keys: List<String>): String? {
        // First try the requested keys directly
        for (key in keys) {
            val element = node[key]
            val time = parseTimeFromElement(element, key == "rtTime" || key == "itdRTTime")
            if (time != null) return time
        }

        // If not found, look into "dateTime" object specially
        val dateTime = node["dateTime"] as? JsonObject
        if (dateTime != null) {
            val isRT = keys.any { it.contains("RT", ignoreCase = true) || it.contains("Estimated", ignoreCase = true) }
            val t = if (isRT) (dateTime["rtTime"] as? JsonPrimitive)?.content ?: (dateTime["time"] as? JsonPrimitive)?.content
                    else (dateTime["time"] as? JsonPrimitive)?.content
            
            if (t != null) {
                val match = Regex("""\b(\d{2}:\d{2})\b""").find(t)
                if (match != null) return match.value
            }
        }
        return null
    }

    private fun parseTimeFromElement(element: JsonElement?, isRT: Boolean): String? {
        if (element is JsonObject) {
            // Case 1: { hour: "HH", minute: "mm" }
            val h = (element["hour"] as? JsonPrimitive)?.content?.padStart(2, '0')
            val m = (element["minute"] as? JsonPrimitive)?.content?.padStart(2, '0')
            if (h != null && m != null) return "$h:$m"

            // Case 2: { time: "HH:mm" } or { rtTime: "HH:mm" }
            val t = if (isRT) (element["rtTime"] as? JsonPrimitive)?.content ?: (element["time"] as? JsonPrimitive)?.content
                    else (element["time"] as? JsonPrimitive)?.content
            if (t != null) {
                val match = Regex("""\b(\d{2}:\d{2})\b""").find(t)
                if (match != null) return match.value
            }
        } else if (element is JsonPrimitive) {
            // Case 3: "HH:mm"
            val timeStr = element.content
            val match = Regex("""\b(\d{2}:\d{2})\b""").find(timeStr)
            if (match != null) return match.value
        }
        return null
    }

    private suspend fun resolveEfaId(stationName: String): String {
        try {
            val responseStr: String = withContext(Dispatchers.IO) {
                // Suche gezielt nach "Bahnhof", um Haltestellen zu vermeiden
                val searchTerm = if (stationName.contains("Bahnhof", ignoreCase = true)) stationName else "$stationName Bahnhof"
                val encodedName = java.net.URLEncoder.encode(searchTerm, "UTF-8")
                val url = "https://efa.sta.bz.it/web/XML_STOPFINDER_REQUEST?language=de&outputFormat=JSON&type_sf=stop&name_sf=$encodedName"
                httpClient.get(url).bodyAsText()
            }
            val root = json.parseToJsonElement(responseStr) as? JsonObject
            val sf = root?.get("stopFinder") as? JsonObject

            val points = getAsList(sf ?: return "66000468", "point")
            // Versuche den ersten Punkt zu finden, der wirklich ein Bahnhof ist
            val bestPoint = points.firstOrNull { 
                val name = (it["name"] as? JsonPrimitive)?.content?.lowercase() ?: ""
                name.contains("bahnhof") || name.contains("stazione")
            } ?: points.firstOrNull()

            val id = (bestPoint?.get("stateless") as? JsonPrimitive)?.content ?: (bestPoint?.get("id") as? JsonPrimitive)?.content
            if (id != null) return id
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return "66000468" // Fallback Bozen
    }

    @Suppress("unused")
    actual fun playSingleBeep() {
        Toolkit.getDefaultToolkit().beep()
    }

    actual fun playDoubleBeep() {
        try {
            Toolkit.getDefaultToolkit().beep()
            Thread.sleep(200)
            Toolkit.getDefaultToolkit().beep()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual fun showNotification(title: String, message: String) {
        if (SystemTray.isSupported()) {
            Thread {
                try {
                    val tray = SystemTray.getSystemTray()
                    val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
                    val g = image.createGraphics()
                    g.color = Color(60, 105, 190)
                    g.fillRect(0, 0, 16, 16)
                    g.dispose()

                    val trayIcon = TrayIcon(image, "Zug-Anzeige Südtirol")
                    trayIcon.isImageAutoSize = true

                    tray.add(trayIcon)
                    trayIcon.displayMessage(title, message, TrayIcon.MessageType.WARNING)

                    Thread.sleep(6000)
                    tray.remove(trayIcon)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
    }
}