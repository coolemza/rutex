package utils

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.ClientWebSocketSession
import io.ktor.client.features.websocket.WebSockets
import io.ktor.client.features.websocket.ws
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.filterNotNull
import kotlinx.coroutines.channels.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KLogger
import org.slf4j.Logger

@KtorExperimentalAPI
class CIOWebSocket: IWebSocket {
    override suspend fun close() {
        client.close()
    }

    override var name = "CIOSocket"
    override var host = "CIOSocketHost"
    override var path = "CIOSocketPath"

    val client = HttpClient(CIO).config { install(WebSockets) }
    lateinit var session: ClientWebSocketSession

    lateinit var logger: Logger

    override suspend fun ws(logger: KLogger, host: String, path: String, block: suspend (IWebSocket) -> Unit,
        handler: suspend (String) -> Unit)
    {
        GlobalScope.launch(Dispatchers.IO) {
            this@CIOWebSocket.logger = logger
            while (isActive) {
                try {
                    client.ws(host = host, path = path) {
                        session = this
                        block(this@CIOWebSocket)

                        for (message in incoming.map { it as? Frame.Text }.filterNotNull()) {
                            handler(message.readText())
                        }
                    }
                } catch (e: Exception) {
                    logger.error(e.message, e)
                }
            }
        }
    }

    override fun send(str: String): Boolean = try {
        session.outgoing.offer(Frame.Text(str))
        true
    } catch (e: Exception) {
        logger.error(e.message, e)
        false
    }

    override suspend fun reconnect() {

    }
}