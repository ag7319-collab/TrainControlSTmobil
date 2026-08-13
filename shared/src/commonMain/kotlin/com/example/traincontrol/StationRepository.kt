package com.example.traincontrol

import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi
import traincontrolstmobil.shared.generated.resources.Res
import kotlinx.coroutines.CancellationException

class StationRepository {
    private var cachedStations: List<StationData>? = null

    @OptIn(ExperimentalResourceApi::class)
    suspend fun getStations(): List<StationData> {
        if (cachedStations != null) return cachedStations!!
        
        return try {
            val bytes = Res.readBytes("files/stations.json")
            val jsonString = bytes.decodeToString()
            val list = Json.decodeFromString<StationList>(jsonString)
            cachedStations = list.stations
            list.stations
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
