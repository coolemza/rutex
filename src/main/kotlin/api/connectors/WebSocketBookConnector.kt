package api.connectors

import mu.KLogger
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance
import utils.DepthChannel
import utils.IWebSocket

class WebSocketBookConnector(val host: String, val path: String, override val kodein: Kodein, val logger: KLogger,
                             val handler: suspend (String, WebSocketBookConnector) -> Unit,
                             val block: suspend (IWebSocket) -> Unit): IConnector, KodeinAware {
    val socket: IWebSocket by instance(tag = "secure")
    val pairChannel = mutableMapOf<Long, DepthChannel>()

    override suspend fun start() {
        socket.name = "Book"
        socket.ws(logger, host, path, { recallBlock(it) }, { recallHandler(it) })
    }

    suspend fun recallHandler(msg: String) = handler(msg, this)

    suspend fun recallBlock(socket: IWebSocket) {
        pairChannel.clear()
        block(socket)
    }

    override suspend fun stop() = socket.close()

    fun send(str: String) = socket.send(str)

    override suspend fun reconnect() {
        logger.info("reconnecting Book socket")
        socket.reconnect()
    }

}