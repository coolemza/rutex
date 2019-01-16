package bot

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KLogger
import okhttp3.*
import java.util.concurrent.TimeUnit

class OKWebSocket: WebSocketListener(), IWebSocket {
//    override var channel = Channel<String>()

    lateinit var socket: WebSocket
    lateinit var job: Job

    lateinit var url: String
    lateinit var logger: KLogger
    override var name = "OkSocket"
    override var host = "OkSocketHost"
    override var path = "OkSocketPath"

    lateinit var handler: suspend (String)-> Unit

    var needConnect = true

    var socketCount = 0L

    override suspend fun ws(logger: KLogger, host: String, path: String, block: suspend (IWebSocket) -> Unit, handler: suspend (String)-> Unit) {
        this.handler = handler
        job = GlobalScope.launch {
            this@OKWebSocket.logger = logger
            this@OKWebSocket.url = "wss://$host$path"
            val request = Request.Builder().url("wss://$host$path").build()

            while (isActive) {
                if (needConnect) {
                    logger.info { "socket: $name connecting.." }
                    connect(request, block)
                }
                delay(TimeUnit.SECONDS.toMillis(2))
            }
        }
    }

    suspend fun connect(request: Request, block: suspend (IWebSocket) -> Unit) {
        while (true) {
            try {
                socketCount = 0L
                socket = OkHttpClient().newWebSocket(request, this@OKWebSocket)
                block(this)
                logger.info { "socket: $name($socket) connected" }
                needConnect = false
                break
            } catch (e: Exception) {
                logger.error(e.message, e)
            }
            delay(TimeUnit.SECONDS.toMillis(5))
        }
    }

    override suspend fun send(str: String) = socket.send(str)

    override fun onMessage(webSocket: WebSocket, text: String) {
        runBlocking { handler(text) }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        logger.warn("socket: $name($socket) url: $url code: $code reason: $reason")
        val res = socket.close(code, reason)
        logger.info("socket: $name close() returns: $res")
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        logger.warn("socket: $name($socket) url: $url code: $code reason: $reason")
        needConnect = true
        logger.info("socket: $name needConnect: $needConnect")
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        response?.let { logger.error(it.body().toString()) } ?: logger.error("response is null")
        logger.error("socket: $name($socket) url: $url ${t.message}", t)

        val res = socket.close(1000, "custom failure")
        logger.info("socket: $name close() returns: $res")

        logger.info("socket: $name reconnect..")
        needConnect = true
    }

    override suspend fun reconnect() {
        val res = socket.close(1000, "reconnect")
        logger.info("socket: $name close() returns: $res")
        needConnect = true
    }

    override suspend fun close() {
        val res = socket.close(1000, "close")
        logger.info("socket: $name close() returns: $res")
        job.cancelAndJoin()
    }
}