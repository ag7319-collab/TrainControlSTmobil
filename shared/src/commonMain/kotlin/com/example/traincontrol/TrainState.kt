package com.example.traincontrol

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object TrainState {
    var trains by mutableStateOf(emptyList<TrainInfo>())
    var stations by mutableStateOf(emptyList<StationData>())
    var isLoading by mutableStateOf(value = false)
    var departureStation by mutableStateOf<StationData?>(null)
    var targetStation by mutableStateOf<StationData?>(null)
}
