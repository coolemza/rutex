package stock

import RutEx.logger
import okhttp3.*
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.slf4j.MDC
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit


class BitfinexPublicSocket(val stock: Bitfinex): WebSocketListener() {

    var  reconnectState = true
    lateinit var socket: WebSocket
    val request = Request.Builder().url("wss://api.bitfinex.com/ws").build()

    fun start() {
        socket = OkHttpClient().newWebSocket(request, this)
        reconnectState = false
    }

    override fun onMessage(webSocket: WebSocket?, text: String?) {
        try {
            MDC.put("threadId", Thread.currentThread().id.toString())
            JSONParser().parse(text).let { res ->
                when (res) {
                    is JSONArray -> when (res[0]) {
                        0L -> { }
                        else -> {
                            stock.pairChannel[res[0]!! as Long]?.let {
                                if (ChronoUnit.SECONDS.between(it.time, LocalDateTime.now()) >= 10) {
                                    logger.warn("heartbeat > 5s for $it")
                                    it.time = LocalDateTime.now()
                                } else {
                                    it.time = LocalDateTime.now()
                                }

                                when (res.size) {
                                    2 -> if (res[1] is JSONArray) stock.onState(res[0]!! as Long, data = res[1]!! as JSONArray)
                                    4 -> stock.onState(res[0]!! as Long, update = res)
                                    else -> logger.error("not handled strange: $text")
                                }
                            } ?: logger.warn("channel not subscribed: ${res[0]!! as Long}")
                        }

                    }
                    is JSONObject -> when (res["event"]) {
                        "info" -> logger.info("socket version: " + res["version"])
                        "subscribed" -> {
                            logger.info("subscribed to ${res["pair"]}, channel: ${res["chanId"]}")
                            val pair = (res["pair"] as String).run { "${dropLast(3)}_${drop(3)}" }.toLowerCase()
                            stock.pairChannel.put(res["chanId"] as Long, DepthChannel(res["pair"] as String, pair))
                        }
                        "unsubscribed" -> {
                            val chanId = res["chanId"] as Long
                            val pair = stock.pairChannel[chanId]?.pair
                            logger.info("unsubscribed from channel: ${res["chanId"]}, pair: $pair")
                            stock.depthBook.pairs.remove(stock.pairChannel[chanId]?.rutPair)
                            logger.info("pair: $pair, removed from state")
                            stock.pairChannel.remove(chanId)
                            socket.send(JSONObject(mapOf("event" to "subscribe", "channel" to "book", "preq" to "R0", "symbol" to pair, "len" to 100)).toJSONString())
                        }
                        else -> logger.info("not handled event: $text")
                    }
                    else -> { }
                }
            }
        } catch (e: Exception) {
            logger.error(e.message, e)
            logger.error("ERRMSG: $text")
        }
    }

    fun unsubscribe(pair: String):Boolean {
        stock.pairChannel.entries.find { it.value.rutPair == pair }?.let {
            logger.info("found item in pairChannel for $pair: $it")
            logger.info("sending unsubscribe to channel: ${it.key}, pair: $pair")
            socket.send(JSONObject(mapOf("event" to "unsubscribe", "chanId" to it.key )).toJSONString())
            return true
        }
        logger.info("item with $pair not found in pairChannel")
        return false
    }

    override fun onOpen(webSocket: WebSocket?, response: Response?) {
        stock.pairs.map { it.key.toUpperCase().split("_").joinToString("") }.forEach {
            socket.send(JSONObject(mapOf("event" to "subscribe", "channel" to "book", "preq" to "R0", "symbol" to it, "len" to 100)).toJSONString())
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        logger.warn("CLOSING: $code $reason")

        if (reason != "rutstop") {
            socket.close(code, reason)
            logger.info("state websocket.close()")
        }
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        logger.warn("CLOSE: $code $reason")

        if (reason != "rutstop") {
            logger.info("reason is not rutstop, launching reconnect state socket")
            start()
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        response?.let { logger.error(it.body().toString()) }
        logger.error(t.message)
        logger.error(t.message, t)

        logger.info("reconnect state socket..")
        start()
    }

    fun reconnect() {
        if (!reconnectState) {
            reconnectState = true
            stop()
            start()
        }
    }

    fun stop () {
        logger.info("closing state socket..")
        socket.close(1000, "rutstop")
    }
}