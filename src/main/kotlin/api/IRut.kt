package api

import data.ControlMsg
import kotlinx.coroutines.channels.Channel

interface IRut {
    val stockList: MutableMap<String, IStock>
    val controlChannel: Channel<ControlMsg>

    suspend fun start()
    suspend fun stop()
    suspend fun getState(): Map<String, Map<String, Map<String, List<Map<String, String>>>>>
}