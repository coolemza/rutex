package utils

import mu.KLogger
import java.time.LocalDateTime

data class DepthChannel(val pair: String, val rutPair: String, var time: LocalDateTime = LocalDateTime.now())

interface IWebSocket {
    var name: String
    var host: String
    var path: String

    fun send(str: String): Boolean
    suspend fun ws(logger: KLogger, host: String, path: String, block: suspend (IWebSocket) -> Unit,
                   handler: suspend (String) -> Unit)
    suspend fun reconnect()
    suspend fun close()
}