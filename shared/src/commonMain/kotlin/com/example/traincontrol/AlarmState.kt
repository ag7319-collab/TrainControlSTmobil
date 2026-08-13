package com.example.traincontrol

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object AlarmState {
    var message by mutableStateOf("")
    var isVisible by mutableStateOf(value = false)
    var trayTooltip by mutableStateOf("Zug-Anzeige Südtirol & Trient")
}
