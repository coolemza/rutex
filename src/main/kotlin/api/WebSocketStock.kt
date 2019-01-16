package stocks

import bot.IWebSocket
import org.kodein.di.Kodein
import org.kodein.di.generic.instance
import api.Stock

abstract class WebSocketStock(kodein: Kodein, name: String) : Stock(kodein, name) {
    val bookSocket: IWebSocket by instance()
    protected val controlSocket: IWebSocket by instance()

    abstract suspend fun onMessage(str: String)
    abstract suspend fun onPrivateMessage(str: String)
    abstract suspend fun loginPrivateSocket(socket: IWebSocket)
    abstract suspend fun loginBookSocket(socket: IWebSocket)

    override suspend fun reconnectPair(pair: String) = reconnectBookSocket()

    suspend fun reconnectControlSocket() {
        logger.info("reconnecting Control socket")
        controlSocket.reconnect()
    }

    private suspend fun reconnectBookSocket() {
        logger.info("reconnecting Book socket")
        bookSocket.reconnect()
    }

    suspend fun startControlSocket(host: String, path: String) {
        syncWallet()
        controlSocket.name = "Control"
        coroutines.add(infoPolling())
        controlSocket.ws(logger, host, path, { loginPrivateSocket(it) }) { onPrivateMessage(it) }
    }

    suspend fun startBookSocket(host: String, path: String) {
        bookSocket.name = "Book"
        bookSocket.ws(logger, host, path, { loginBookSocket(it) }) { onMessage(it) }
    }

    override suspend fun stop() {
        bookSocket.close()
        controlSocket.close()
        shutdown()
    }

    fun remove(pair: String) = internalBook.remove(pair) //FIXME: refactor to remove access to internalBook
}