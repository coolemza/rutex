package api.connectors

import mu.KLogger
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance
import utils.IWebSocket

class WebSocketControlConnector(val host: String, private val path: String, override val kodein: Kodein,
                                val logger: KLogger, private val block: suspend (IWebSocket) -> Unit,
                                val handler: suspend (String) -> Unit): IConnector, KodeinAware
{
    private val socket: IWebSocket by instance(tag = "secure")

    override suspend fun start() {
        socket.name = "Control"
        socket.ws(logger, host, path, block, handler)
    }

    override suspend fun stop() = socket.close()

    fun send(str: String) = socket.send(str)

    override suspend fun reconnect() {
        logger.info("reconnecting Control socket")
        socket.reconnect()
    }
}