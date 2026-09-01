package com.example.traincontrol

import kotlinx.serialization.Serializable

@Serializable
data class TrainInfo(
    val categoryNumber: String,
    val destination: String,
    val time: String,
    val delay: String,
    val platform: String,
    val hasDelay: Boolean,
    val isBus: Boolean = false,
    val stopsAtTarget: Boolean? = null,
    val rfiDelay: String? = null,
    val rfiStatus: String? = null,
    val vtDelay: String? = null,
    val vtStatus: String? = null,
    val lineTerminal: String? = null,
) {
    val isCancelled: Boolean
        get() = (delay == "entfällt") || (rfiStatus == "entfällt") || (vtStatus == "entfällt")

    val maxDelayMinutes: Int
        get() {
            fun parse(s: String?): Int {
                if (s == null || s.contains("-")) return 0
                return s.filter { it.isDigit() }.toIntOrNull() ?: 0
            }
            return maxOf(parse(delay), maxOf(parse(rfiDelay), parse(vtDelay)))
        }

    val hasAnyIssue: Boolean
        get() = isCancelled || maxDelayMinutes >= 6
}

@Serializable
data class StationData(
    val name: String,
    val placeId: String,
    val efaId: String? = null,
    val lat: Double,
    val lon: Double,
    val aliases: List<String>,
    val isSelectable: Boolean = true,
)

@Serializable
data class CategoryFilter(
    val prefKey: String,
    val label: String,
    val searchTerms: List<String>,
    val defaultState: Boolean,
)

@Serializable
data class StationList(
    val schemaVersion: Int,
    val stations: List<StationData>,
)
