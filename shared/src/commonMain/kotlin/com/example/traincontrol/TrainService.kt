package com.example.traincontrol

import com.russhwolf.settings.Settings

expect class TrainService() {
    suspend fun fetchAndParseTrains(
        fromStation: StationData,
        targetStation: StationData,
        settings: Settings,
        allStations: List<StationData>,
    ): List<TrainInfo>

    fun playSingleBeep()

    fun playDoubleBeep()

    fun showNotification(title: String, message: String)
}
