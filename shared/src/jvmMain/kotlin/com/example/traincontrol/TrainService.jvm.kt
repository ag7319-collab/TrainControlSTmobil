@file:OptIn(ExperimentalMultiplatform::class)

package com.example.traincontrol

import kotlin.ExperimentalMultiplatform
import com.russhwolf.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
        val limit = 10

        val allowReg = settings.getBoolean("cat_reg", defaultValue = true)
        val allowRv = settings.getBoolean("cat_rv", defaultValue = true)

        // Die Bus-Checkbox aus den Settings wird hier ignoriert,
        // da sie nur für RFI-Ersatzbusse gedacht ist.
        // Für Südtirol Mobil (EFA) werden Busse grundsätzlich ausgefiltert.
        val allowTrenord = settings.getBoolean("cat_tn_rj", defaultValue = false)
        val allowFreccia = settings.getBoolean("cat_fv_freccia", defaultValue = false)
        val allowItalo = settings.getBoolean("cat_fv_italo", defaultValue = false)
        val allowIC = settings.getBoolean("cat_fv_ic", defaultValue = false)

        val now = LocalDateTime.now()

        for (attempt in 1..2) {
            rawTrainList.clear()

            try {
                val efaFromId = fromStation.efaId ?: resolveEfaId(fromStation.name)
                val efaToId = targetStation.efaId ?: resolveEfaId(targetStation.name)

                // Wir machen zwei Abfragen: Vergangenheit (-60m) und Zukunft (jetzt)
                // Da der Server oft nur 5 Ergebnisse liefert, stellen wir so sicher, dass
                // wir sowohl verspätete alte Züge als auch neue Züge sehen.
                val queryOffsets = listOf(60L, 0L)

                for (offset in queryOffsets) {
                    val queryStart = now.minusMinutes(offset)
                    val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
                    val timeFormatter = DateTimeFormatter.ofPattern("HHmm")

                    val dateStr = queryStart.format(dateFormatter)
                    val timeStr = queryStart.format(timeFormatter)

                    val url =
                        "https://efa.sta.bz.it/web/XML_TRIP_REQUEST2" +
                                "?sessionID=0" +
                                "&requestID=0" +
                                "&name_origin=$efaFromId" +
                                "&type_origin=stop" +
                                "&name_destination=$efaToId" +
                                "&type_destination=stop" +
                                "&itdDate=$dateStr" +
                                "&itdTime=$timeStr" +
                                "&useRealtime=1" +
                                "&outputFormat=JSON" +
                                "&language=de" +
                                "&odvMacro=true" +
                                "&ptOptionsActive=1" +
                                "&itOptionsActive=1" +
                                "&inclMOT_0=1" +
                                "&inclMOT_1=1" +
                                "&inclMOT_2=1" +
                                "&inclMOT_3=1" +
                                "&inclMOT_4=0" +
                                "&inclMOT_5=0" +
                                "&inclMOT_6=0" +
                                "&inclMOT_7=0" +
                                "&inclMOT_8=0" +
                                "&inclMOT_9=0" +
                                "&inclMOT_10=0" +
                                "&inclMOT_11=0" +
                                "&calcNumberOfTrips=10"

                    val responseStr: String = withContext(Dispatchers.IO) {
                        val resp = httpClient.get(url)
                        resp.bodyAsText()
                    }

                    val root = (json.parseToJsonElement(responseStr) as? JsonObject) ?: continue
                    val tripResponse = root["tripResponse"] as? JsonObject

                    val trips = getAsList(root, "journey")
                        .ifEmpty { getAsList(root, "journeys") }
                        .ifEmpty { getAsList(root, "trip") }
                        .ifEmpty { getAsList(root, "trips") }
                        .ifEmpty { getAsList(tripResponse ?: root, "journey") }
                        .ifEmpty { getAsList(tripResponse ?: root, "trip") }
                        .ifEmpty { getAsList(tripResponse ?: root, "tripList") }

                    println("DEBUG: Found ${trips.size} trips for offset $offset")

                    for (trip in trips) {
                        val legs = getAsList(trip, "leg")
                            .ifEmpty { getAsList(trip, "legs") }
                            .ifEmpty { getAsList(trip, "legList") }

                        var tripBanned = false
                        for (legObj in legs) {
                            val transp = (legObj["transportation"] as? JsonObject) ?: (legObj["mode"] as? JsonObject)
                            val tName = (transp?.get("name") as? JsonPrimitive)?.content ?: ""
                            val isWalk = tName.contains("Fußweg", ignoreCase = true) || (legObj["isWalk"] as? JsonPrimitive)?.booleanOrNull == true
                            if (isWalk) continue

                            val upper = tName.uppercase()
                            val isBus = (upper.contains("BUS") || upper.contains("SAD") || upper.contains("SASA") || upper.contains("LINIE")) &&
                                    !upper.contains(" R ") && !upper.startsWith("R ") && !upper.contains("RV") && !upper.contains("RE ") && !upper.contains("EC") && !upper.contains("RJ")

                            val isTrenordOrRJ = upper.contains("RJ") || upper.contains("RAILJET") || upper.contains("EC")
                            val isFreccia = upper.contains("FRECCIA") || upper.contains("FR ")
                            val isItalo = upper.contains("ITALO")
                            val isIC = upper.contains("INTERCITY") || upper.contains("IC ")
                            val isRv = upper.contains("RV") || upper.contains("REGIONALE VELOCE")
                            val isReg = !isBus && !isRv && !isTrenordOrRJ && !isFreccia && !isItalo && !isIC

                            if (isBus || (isTrenordOrRJ && !allowTrenord) || (isFreccia && !allowFreccia) || (isItalo && !allowItalo) || (isIC && !allowIC) || (isRv && !allowRv) || (isReg && !allowReg)) {
                                tripBanned = true
                                break
                            }
                        }
                        if (tripBanned) continue

                        val mainLeg = legs.firstOrNull { legObj ->
                            val transp = (legObj["transportation"] as? JsonObject) ?: (legObj["mode"] as? JsonObject)
                            val tName = (transp?.get("name") as? JsonPrimitive)?.content ?: ""
                            !tName.contains("Fußweg", ignoreCase = true) && (legObj["isWalk"] as? JsonPrimitive)?.booleanOrNull != true
                        } ?: continue

                        val points = getAsList(mainLeg, "point").ifEmpty { getAsList(mainLeg, "points") }
                        val originNode = (mainLeg["origin"] as? JsonObject) ?: points.firstOrNull { ((it["usage"] as? JsonPrimitive)?.content == "departure") } ?: points.firstOrNull()
                        val transpNode = (mainLeg["transportation"] as? JsonObject) ?: (mainLeg["mode"] as? JsonObject)

                        if (originNode == null || transpNode == null) continue

                        val transpName = (transpNode["name"] as? JsonPrimitive)?.content ?: (transpNode["disassembledName"] as? JsonPrimitive)?.content ?: "Zug"
                        val finalDest = ((transpNode["destination"] as? JsonObject)?.get("name") as? JsonPrimitive)?.content ?: targetStation.name
                        val upperCat = transpName.uppercase()
                        val isBus = (upperCat.contains("BUS") || upperCat.contains("SAD") || upperCat.contains("SASA") || upperCat.contains("LINIE")) &&
                                !upperCat.contains(" R ") && !upperCat.startsWith("R ") && !upperCat.contains("RV") && !upperCat.contains("RE ")

                        val planDate = extractDate(originNode, listOf("itdTime", "dateTime", "departureTimePlanned", "date")) ?: now.format(dateFormatter)
                        val planTime = extractTime(originNode, listOf("itdTime", "dateTime", "departureTimePlanned", "time"))
                        val realTime = extractTime(originNode, listOf("itdRTTime", "realDateTime", "departureTimeEstimated", "rtTime")) ?: planTime

                        if (planTime == null) continue

                        val actualDeparture = calculateActualDepartureDateTime(planDate, planTime, realTime)
                        if (!actualDeparture.isAfter(now.minusMinutes(1))) continue

                        // Filter: Züge, die mehr als 5 Stunden in der Zukunft liegen, ausblenden.
                        // Das verhindert, dass spätabends bereits die Pendlerzüge von morgen früh angezeigt werden.
                        if (actualDeparture.isAfter(now.plusHours(5))) continue

                        if (rawTrainList.any { it.categoryNumber == transpName && it.time == planTime }) continue

                        rawTrainList.add(
                            TrainInfo(
                                categoryNumber = transpName,
                                destination = finalDest,
                                time = planTime,
                                delay = "pünktlich", 
                                platform = (originNode["platformName"] as? JsonPrimitive)?.content ?: "-",
                                hasDelay = false,
                                isBus = isBus,
                                stopsAtTarget = true,
                            )
                        )

                        val idx = rawTrainList.size - 1
                        if (realTime != null && realTime != planTime) {
                            val plannedTime = parseLocalTime(planTime)
                            val actualTime = parseLocalTime(realTime)
                            if (plannedTime != null && actualTime != null) {
                                val pTotal = plannedTime.hour * 60 + plannedTime.minute
                                var rTotal = actualTime.hour * 60 + actualTime.minute
                                if (rTotal < pTotal && (pTotal - rTotal) > 720) rTotal += 1440
                                val delayMins = rTotal - pTotal
                                if (delayMins > 0) {
                                    rawTrainList[idx] = rawTrainList[idx].copy(delay = "+$delayMins Min.", hasDelay = true)
                                }
                            }
                        }

                        val isCancelled = (originNode["isCancelled"] as? JsonPrimitive)?.content == "1" || (originNode["isCancelled"] as? JsonPrimitive)?.booleanOrNull == true
                        if (isCancelled) {
                            rawTrainList[idx] = rawTrainList[idx].copy(delay = "entfällt", hasDelay = true)
                        }

                        if (rawTrainList.size >= limit) break
                    }
                }

                /*
                 * =========================================================
                 * RFI GEGENCHECK
                 * =========================================================
                 */
                try {

                    val rfiUrl =
                        "https://iechub.rfi.it/ArriviPartenze/arrivalsdepartures/Monitor" +
                                "?placeId=${fromStation.placeId}" +
                                "&arrivals=False"

                    val rfiDoc =
                        withContext(Dispatchers.IO) {
                            try {
                                Jsoup.connect(rfiUrl)
                                    .timeout(8000)
                                    .userAgent(
                                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                                "Chrome/120.0.0.0 Safari/537.36"
                                    )
                                    .get()
                            } catch (_: Exception) {
                                null
                            }
                        }

                    if (rfiDoc != null) {

                        val rfiRows =
                            rfiDoc.select("tr")

                        for ((i, train) in rawTrainList.withIndex()) {

                            val efaNum =
                                train.categoryNumber
                                    .filter {
                                        it.isDigit()
                                    }

                            if (efaNum.isBlank()) {
                                continue
                            }

                            /*
                             * Suche in den RFI-Zeilen nach der Zugnummer.
                             */
                            val matchedRow =
                                rfiRows.firstOrNull { row ->

                                    val rowText =
                                        row.text()

                                    rowText.contains(
                                        efaNum
                                    )
                                }

                            if (matchedRow != null) {

                                val cols =
                                    matchedRow.select("td")

                                if (cols.size >= 5) {

                                    /*
                                     * Spaltenindex beim RFI Monitor:
                                     *
                                     * [Kategorie+Nr]
                                     * [Ziel]
                                     * [Gleis]
                                     * [Zeit]
                                     * [Verspätung]
                                     * ...
                                     *
                                     * Wir suchen das Zeitfeld.
                                     */
                                    val timeRegex =
                                        Regex(
                                            """\b\d{2}:\d{2}\b"""
                                        )

                                    val colTexts =
                                        cols.map {
                                            it.text().trim()
                                        }

                                    val timeIdx =
                                        colTexts.indexOfFirst {
                                            timeRegex.containsMatchIn(
                                                it
                                            )
                                        }

                                    if (
                                        timeIdx != -1 &&
                                        colTexts.size >
                                        timeIdx + 1
                                    ) {

                                        val rawDelay =
                                            colTexts[
                                                timeIdx + 1
                                            ]

                                        val isCancelled =
                                            rawDelay.contains(
                                                "SOP",
                                                ignoreCase = true
                                            ) ||
                                                    rawDelay.contains(
                                                        "CANC",
                                                        ignoreCase = true
                                                    ) ||
                                                    matchedRow.text()
                                                        .contains(
                                                            "SOPPRESSO",
                                                            ignoreCase = true
                                                        )

                                        val statusText =
                                            when {
                                                isCancelled ->
                                                    "entfällt"

                                                rawDelay.isBlank() ||
                                                        rawDelay == "0" ->
                                                    "pünktlich"

                                                else ->
                                                    "Verspätung"
                                            }

                                        val delayDisplay =
                                            when {
                                                isCancelled ->
                                                    ""

                                                rawDelay.isBlank() ||
                                                        rawDelay == "0" ->
                                                    "+0"

                                                rawDelay.all {
                                                    it.isDigit()
                                                } ->
                                                    "+$rawDelay"

                                                else ->
                                                    rawDelay
                                            }

                                        rawTrainList[i] =
                                            train.copy(
                                                rfiDelay =
                                                    delayDisplay,
                                                rfiStatus =
                                                    statusText
                                            )
                                    }
                                }
                            }
                        }
                    }

                } catch (e: Exception) {

                    println(
                        "DEBUG: RFI-Monitor Cross-Check failed: " +
                                e.message
                    )
                }

                // ---------------------------------------------------------

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {
                e.printStackTrace()
            }

            /*
             * Wenn wir bereits Züge gefunden haben, brauchen wir
             * keinen zweiten Versuch.
             */
            if (rawTrainList.isNotEmpty()) {
                break
            }

            if (attempt == 1) {
                delay(1.seconds)
            }
        }

        /*
         * Wir sortieren weiterhin nach der geplanten Abfahrtszeit.
         *
         * Das bedeutet:
         *
         * 19:00 +60
         * 19:30 pünktlich
         * 19:45 pünktlich
         *
         * Der verspätete Zug bleibt also an seiner ursprünglichen
         * Fahrplanposition.
         */
        return rawTrainList.sortedBy {
            it.time
        }
    }

    /**
     * Wandelt die geplante und tatsächliche Uhrzeit in eine
     * LocalDateTime um.
     *
     * Beispiel:
     *
     * planDate = 20260813
     * planTime = 19:00
     * realTime = 20:00
     *
     * Ergebnis:
     * 2026-08-13T20:00
     *
     * Damit kann anschließend sauber mit "now" verglichen werden.
     */
    private fun calculateActualDepartureDateTime(
        planDate: String,
        planTime: String,
        realTime: String?
    ): LocalDateTime {

        val dateFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd")

        val date =
            try {
                LocalDate.parse(
                    planDate,
                    dateFormatter
                )
            } catch (_: Exception) {
                LocalDate.now()
            }

        val plannedTime =
            parseLocalTime(planTime)
                ?: LocalTime.MIDNIGHT

        val actualTime =
            parseLocalTime(realTime ?: planTime)
                ?: plannedTime

        var actualDate = date

        val plannedMinutes =
            plannedTime.hour * 60 +
                    plannedTime.minute

        val actualMinutes =
            actualTime.hour * 60 +
                    actualTime.minute

        /*
         * Mitternachtswechsel erkennen.
         *
         * Beispiel:
         *
         * geplant: 23:40
         * real:    00:30
         *
         * Die tatsächliche Abfahrt ist am nächsten Tag.
         *
         * Wir gehen nur dann von einem Tageswechsel aus,
         * wenn die Differenz größer als 12 Stunden ist.
         */
        if (
            actualMinutes < plannedMinutes &&
            (plannedMinutes - actualMinutes) > 720
        ) {
            actualDate =
                actualDate.plusDays(1)
        }

        return LocalDateTime.of(
            actualDate,
            actualTime
        )
    }

    /**
     * Wandelt "HH:mm" in LocalTime um.
     *
     * Akzeptiert auch Werte, die in einem längeren String
     * enthalten sind.
     */
    private fun parseLocalTime(
        time: String?
    ): LocalTime? {

        if (time == null) {
            return null
        }

        val match =
            Regex(
                """\b(\d{1,2}):(\d{2})\b"""
            ).find(time)
                ?: return null

        val hour =
            match.groupValues[1]
                .toIntOrNull()
                ?: return null

        val minute =
            match.groupValues[2]
                .toIntOrNull()
                ?: return null

        if (hour !in 0..23) {
            return null
        }

        if (minute !in 0..59) {
            return null
        }

        return LocalTime.of(
            hour,
            minute
        )
    }

    private fun getAsList(
        node: JsonObject,
        key: String
    ): List<JsonObject> {

        val element =
            node[key]
                ?: node["${key}s"]
                ?: return emptyList()

        return when (element) {

            is JsonArray ->
                element.filterIsInstance<JsonObject>()

            is JsonObject ->
                listOf(element)

            else ->
                emptyList()
        }
    }

    /**
     * Sucht nach einem Datum in verschiedenen möglichen
     * EFA-Feldformaten.
     */
    private fun extractDate(
        node: JsonObject,
        keys: List<String>
    ): String? {

        for (key in keys) {

            val element =
                node[key]

            if (element is JsonObject) {

                val y =
                    (element["year"] as? JsonPrimitive)
                        ?.content

                val m =
                    (element["month"] as? JsonPrimitive)
                        ?.content
                        ?.padStart(2, '0')

                val d =
                    (element["day"] as? JsonPrimitive)
                        ?.content
                        ?.padStart(2, '0')

                if (
                    y != null &&
                    m != null &&
                    d != null
                ) {
                    return "$y$m$d"
                }

                val dateStr =
                    (element["date"] as? JsonPrimitive)
                        ?.content

                if (dateStr != null) {

                    /*
                     * Format:
                     * dd.MM.yyyy
                     */
                    val parts =
                        dateStr.split(".")

                    if (parts.size == 3) {
                        return "${parts[2]}${parts[1]}${parts[0]}"
                    }
                }

            } else if (element is JsonPrimitive) {

                val dateStr =
                    element.content

                if (dateStr.contains(".")) {

                    val parts =
                        dateStr.split(".")

                    if (parts.size == 3) {
                        return "${parts[2]}${parts[1]}${parts[0]}"
                    }
                }
            }
        }

        return null
    }

    /**
     * Sucht nach einer Uhrzeit in verschiedenen möglichen
     * EFA-Feldformaten.
     */
    private fun extractTime(
        node: JsonObject,
        keys: List<String>
    ): String? {

        /*
         * Zuerst die angegebenen Felder direkt versuchen.
         */
        for (key in keys) {

            val element =
                node[key]

            val time =
                parseTimeFromElement(
                    element,
                    key == "rtTime" ||
                            key == "itdRTTime"
                )

            if (time != null) {
                return time
            }
        }

        /*
         * Falls dateTime ein Objekt ist, darin
         * gezielt nach rtTime bzw. time suchen.
         */
        val dateTime =
            node["dateTime"] as? JsonObject

        if (dateTime != null) {

            val isRT =
                keys.any {
                    it.contains(
                        "RT",
                        ignoreCase = true
                    ) ||
                            it.contains(
                                "Estimated",
                                ignoreCase = true
                            )
                }

            val t =
                if (isRT) {

                    (dateTime["rtTime"] as? JsonPrimitive)
                        ?.content
                        ?: (
                                dateTime["time"]
                                        as? JsonPrimitive
                                )?.content

                } else {

                    (dateTime["time"] as? JsonPrimitive)
                        ?.content
                }

            if (t != null) {

                val match =
                    Regex(
                        """\b(\d{2}:\d{2})\b"""
                    ).find(t)

                if (match != null) {
                    return match.value
                }
            }
        }

        return null
    }

    /**
     * Parst ein einzelnes EFA-Zeitfeld.
     */
    private fun parseTimeFromElement(
        element: JsonElement?,
        isRT: Boolean
    ): String? {

        if (element is JsonObject) {

            /*
             * Case 1:
             *
             * {
             *   "hour": "19",
             *   "minute": "30"
             * }
             */
            val h =
                (element["hour"] as? JsonPrimitive)
                    ?.content
                    ?.padStart(2, '0')

            val m =
                (element["minute"] as? JsonPrimitive)
                    ?.content
                    ?.padStart(2, '0')

            if (
                h != null &&
                m != null
            ) {
                return "$h:$m"
            }

            /*
             * Case 2:
             *
             * {
             *   "time": "19:30"
             * }
             *
             * oder:
             *
             * {
             *   "rtTime": "19:30"
             * }
             */
            val t =
                if (isRT) {

                    (element["rtTime"] as? JsonPrimitive)
                        ?.content
                        ?: (
                                element["time"]
                                        as? JsonPrimitive
                                )?.content

                } else {

                    (element["time"] as? JsonPrimitive)
                        ?.content
                }

            if (t != null) {

                val match =
                    Regex(
                        """\b(\d{2}:\d{2})\b"""
                    ).find(t)

                if (match != null) {
                    return match.value
                }
            }

        } else if (element is JsonPrimitive) {

            /*
             * Case 3:
             *
             * "19:30"
             */
            val timeStr =
                element.content

            val match =
                Regex(
                    """\b(\d{2}:\d{2})\b"""
                ).find(timeStr)

            if (match != null) {
                return match.value
            }
        }

        return null
    }

    private suspend fun resolveEfaId(
        stationName: String
    ): String {

        try {

            val responseStr: String =
                withContext(Dispatchers.IO) {

                    /*
                     * Suche gezielt nach "Bahnhof",
                     * um Haltestellen möglichst zu vermeiden.
                     */
                    val searchTerm =
                        if (
                            stationName.contains(
                                "Bahnhof",
                                ignoreCase = true
                            )
                        ) {
                            stationName
                        } else {
                            "$stationName Bahnhof"
                        }

                    val encodedName =
                        java.net.URLEncoder.encode(
                            searchTerm,
                            "UTF-8"
                        )

                    val url =
                        "https://efa.sta.bz.it/web/XML_STOPFINDER_REQUEST" +
                                "?language=de" +
                                "&outputFormat=JSON" +
                                "&type_sf=stop" +
                                "&name_sf=$encodedName"

                    httpClient
                        .get(url)
                        .bodyAsText()
                }

            val root =
                json.parseToJsonElement(
                    responseStr
                ) as? JsonObject

            val sf =
                root?.get("stopFinder")
                        as? JsonObject

            val points =
                getAsList(
                    sf ?: return "66000468",
                    "point"
                )

            /*
             * Versuche zuerst einen Punkt zu finden,
             * der tatsächlich ein Bahnhof ist.
             */
            val bestPoint =
                points.firstOrNull {

                    val name =
                        (
                                it["name"]
                                        as? JsonPrimitive
                                )?.content
                            ?.lowercase()
                            ?: ""

                    name.contains("bahnhof") ||
                            name.contains("stazione")
                }
                    ?: points.firstOrNull()

            val id =
                (
                        bestPoint?.get("stateless")
                                as? JsonPrimitive
                        )?.content
                    ?: (
                            bestPoint?.get("id")
                                    as? JsonPrimitive
                            )?.content

            if (id != null) {
                return id
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        /*
         * Fallback Bozen.
         */
        return "66000468"
    }

    @Suppress("unused")
    actual fun playSingleBeep() {
        Toolkit
            .getDefaultToolkit()
            .beep()
    }

    actual fun playDoubleBeep() {

        try {

            Toolkit
                .getDefaultToolkit()
                .beep()

            Thread.sleep(200)

            Toolkit
                .getDefaultToolkit()
                .beep()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    actual fun showNotification(
        title: String,
        message: String
    ) {

        if (SystemTray.isSupported()) {

            Thread {

                try {

                    val tray =
                        SystemTray.getSystemTray()

                    val image =
                        BufferedImage(
                            16,
                            16,
                            BufferedImage.TYPE_INT_ARGB
                        )

                    val g =
                        image.createGraphics()

                    g.color =
                        Color(
                            60,
                            105,
                            190
                        )

                    g.fillRect(
                        0,
                        0,
                        16,
                        16
                    )

                    g.dispose()

                    val trayIcon =
                        TrayIcon(
                            image,
                            "Zug-Anzeige Südtirol"
                        )

                    trayIcon.isImageAutoSize =
                        true

                    tray.add(
                        trayIcon
                    )

                    trayIcon.displayMessage(
                        title,
                        message,
                        TrayIcon.MessageType.WARNING
                    )

                    Thread.sleep(6000)

                    tray.remove(
                        trayIcon
                    )

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.start()
        }
    }
}