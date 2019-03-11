package api

import data.ControlMsg
import kotlinx.coroutines.channels.SendChannel

interface IState {
    val stockList: Map<String, IStock>
    val controlChannel: SendChannel<ControlMsg>

    suspend fun start(startBots: Boolean = true)
    suspend fun stop()
}