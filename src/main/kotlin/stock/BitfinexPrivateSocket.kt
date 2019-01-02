package stock

import RutEx.logger
import database.StockKey
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.slf4j.MDC
import java.math.BigInteger
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BitfinexPrivateSocket(val stock: Bitfinex, val key: StockKey): WebSocketListener() {
    var reconnectState = true
    lateinit var socket: WebSocket
    val request = Request.Builder().url("wss://api.bitfinex.com/ws/2").build()

    var socketCount = 0L

    fun start() {
        socketCount = 0L
        socket = OkHttpClient().newWebSocket(request, this)

        socket.send(JSONObject(mapOf("event" to "conf", "flags" to 65536)).toJSONString())

        val nonce = Instant.ofEpochSecond(0L).until(Instant.now(), ChronoUnit.SECONDS).toString()
        val payload = "AUTH$nonce"
        val sign = Mac.getInstance("HmacSHA384").apply {
            init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA384"))
        }.doFinal(payload.toByteArray())

        JSONObject(mapOf("apiKey" to key.key,
                "event" to "auth",
                "authPayload" to payload,
                "authNonce" to nonce,
                "authSig" to String.format("%X", BigInteger(1, sign)).toLowerCase())).let { socket.send(it.toJSONString()) }
        reconnectState = false
    }

    private fun CheckSequence(count: Long): Boolean {
        if (socketCount != count - 1) {
            logger.error("sequence brocken, socketCount: $socketCount, count: $count")
            return false
        } else {
            socketCount = count
            return true
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        try {
            MDC.put("threadId", Thread.currentThread().id.toString())
            val res = JSONParser().parse(text)
            when (res) {
                is JSONArray -> when (res[0]) {
                    0L -> {
                        (if (res[1] == "hb" && res.size == 3) 2 else 3).let {
                            if (!CheckSequence(res[it] as Long)) {
                                logger.error("failed on: $text")
                                reconnect()
                            }
                        }

                        when (res[1]) {
                            "ws" -> stock.onWallet(null, res[2] as List<List<*>>)
                            "wu" -> GlobalScope.launch { stock.onWallet(res[2] as List<*>, null) }
                            "os" -> (res[2] as List<*>).forEach { stock.onOrder(it as List<*>) }
                            "on", "ou", "oc" -> GlobalScope.launch { stock.onOrder(res[2] as List<*>) }
                            "n" -> GlobalScope.launch { stock.OnNotify(res[2] as List<*>) }
                            "te" -> GlobalScope.launch { stock.onTradeExecuted(res[2] as List<*>) }
                            "tu" -> GlobalScope.launch { stock.onTradeUpdate(res[2] as List<*>) }
                            else -> GlobalScope.launch { logger.info("not handled: " + text) }
                        }
                    }
                    else -> {
                        logger.error("wrong channel - $text")
                    }
                }
                is JSONObject -> when (res["event"]) {
                    "info" -> logger.info("socket version: " + res["version"])
                    "auth" -> {
                        if (res["status"] == "FAILED") {
                            logger.error(text)
                            reconnect()
                        }
                    }
                    else -> logger.info("not handled event: $text")
                }
            }
        } catch (e: Exception) {
            logger.error(e.message, e)
            logger.error("ERRMSG: $text")
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        logger.warn("CLOSING: $code $reason")

        if (reason != "rutstop") {
            socket.close(code, reason)
            logger.info("state trade websocket.close()")
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        logger.warn("CLOSE: $code $reason")

        if (reason != "rutstop") {
            logger.info("reason is not rutstop, launching reconnect trade socket")
            start()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        reconnectState = true
        response?.let { logger.error(it.body().toString()) } ?: logger.error("response is null")
        logger.error(t.message)
        logger.error(t.message, t)

        logger.info("reconnect trade socket..")
        start()
    }

    private fun reconnect() {
        if (!reconnectState) {
            reconnectState = true
            stop()
            start()
        }
    }

    fun stop () {
        logger.info("closing trade socket..")
        socket.close(1000, "rutstop")
    }
}