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
    val lineTerminal: String? = null,
)

@Serializable
data class StationData(
    val name: String,
    val placeId: String,
    val efaId: String? = null,
    val lat: Double,
    val lon: Double,
    val aliases: List<String>,
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
