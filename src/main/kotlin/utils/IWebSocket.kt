package bot

import kotlinx.coroutines.channels.Channel
import mu.KLogger

interface IWebSocket {
    var name: String
    var host: String
    var path: String
//    var channel: Channel<String>

    suspend fun send(str: String): Boolean
    suspend fun ws(logger: KLogger, host: String, path: String, block: suspend (IWebSocket) -> Unit, handler: suspend (String) -> Unit)
    suspend fun reconnect()
    suspend fun close()
}