package stock

import data.DepthBook
import data.Depth
import data.Order
import database.*
import database.RutData.P
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.kodein.di.Kodein
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDateTime
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class DepthChannel(val pair: String, val rutPair: String, var time: LocalDateTime = LocalDateTime.now())

class Bitfinex(kodein: Kodein): WebSocketStock(kodein, Bitfinex::class.simpleName!!) {
    override suspend fun cancelOrders(orders: List<Order>): List<Job>? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun deposit(lastId: Long, transfers: List<Transfer>, key: StockKey): Pair<Long, List<TransferUpdate>> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun handleError(res: Any): Any? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun orderInfo(order: Order, updateTotal: Boolean, key: StockKey): OrderUpdate? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    private val coroutines = mutableListOf<Job>()

//    Withdrawal Types
//    "bitcoin", "litecoin", "ethereum", "ethereumc", "tetheruso", "zcash", "monero", "iota", "ripple", "dash",
// "adjustment", "wire", "eos", "santiment", "omisego", "bcash", "neo", "metaverse", "qtum", "aventus", "eidoo",
// "datacoin", "tetheruse", "bgold", "qash", "yoyow", "golem", "status", "tethereue", "bat", "mna", "fun", "zrx",
// "tnb", "spk", "trx", "rcn", "rlc", "aid", "sng", "rep", "elf"
    val withdrawType = mapOf("btc" to "bitcoin", "ltc" to "litecoin", "eth" to "ethereum")
    val withdrawUrl = mapOf("https://api.bitfinex.com/v1/withdraw" to "/v1/withdraw")

    val pairChannel = mutableMapOf<Long, DepthChannel>()
    lateinit var stateSocket: BitfinexPublicSocket
    lateinit var controlSocket: BitfinexPrivateSocket

    val walletUrl = mapOf("https://api.bitfinex.com/v1/balances" to "/v1/balances")
    val orderUrl = mapOf("https://api.bitfinex.com/v1/order/status" to "/v1/order/status")
//    val tradeUrl = mapOf("https://api.bitfinex.com/v1/order/new/multi" to "/v1/order/new/multi")
//    val depthUrl = pairs.map { "https://api.bitfinex.com/v1/book/${it.key.split("_").joinToString("")}" to it.key }.toMap()
//    val infoUrl = mapOf("https://api.bitfinex.com/v1/symbols" to "")
//    val activeUrl = mapOf("https://api.bitfinex.com/v1/orders" to "/v1/orders")

    //TODO bitfinex has limit of 100 orders per pair
    fun getUrl(cmd: String) = mapOf("https://api.bitfinex.com/v1/$cmd" to "/v1/$cmd")
    private fun privateApi(cmd: String) = "https://api.bitfinex.com/v1/$cmd"

    fun getRutPair(pair: String) = pair.toLowerCase().let { it.substring(0..2) + "_" + it.substring(3..5) }

//    suspend override fun getDepth(updateTo: DepthBook?, pair: String?): DepthBook? {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//
//    override suspend fun getBalance(): Map<String, BigDecimal>? {
//
//        return ParseResponse(state.SendRequest(walletUrl.keys.first(), getApiRequest(state.getWalletKey(), walletUrl)))?.let {
//            (it as List<*>).filter { (it as Map<*, *>)["type"] == "exchange" }
//                    .map { (it as Map<*, *>)["currency"] as String to BigDecimal((it)["available"] as String) }
//                    .filter { state.currencies.containsKey(it.first) }.toMap()
//        }
//    }

    override fun balance(key: StockKey): Map<String, BigDecimal>? = apiRequest("balances", key)?.let {
        (((it as Map<*, *>)["return"] as Map<*, *>)["funds"] as Map<*, *>)
                .filter { currencies.containsKey(it.key.toString()) }
                .map { it.key.toString() to BigDecimal(it.value.toString()) }.toMap()
    }

    override fun apiRequest(cmd: String, key: StockKey, data: Map<String, String>?, timeOut: Long): Any? {
        val params = JSONObject(mutableMapOf("request" to privateApi(cmd), "nonce" to "${++key.nonce}"))
        if (null != data) {
            when (data) {
                is JSONArray -> params.put("orders", data)
                is Map<*, *> -> params.putAll(data)
            }
        }

        val payload = Base64.getEncoder().encodeToString(params.toJSONString().toByteArray())

        val mac = Mac.getInstance("HmacSHA384")
        mac.init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA384"))
        val sign = String.format("%096x", BigInteger(1, mac.doFinal(payload.toByteArray()))).toLowerCase()

        return parseJsonResponse(SendRequest(privateApi(cmd), mapOf("X-BFX-APIKEY" to key.key, "X-BFX-PAYLOAD" to payload, "X-BFX-SIGNATURE" to sign), payload))
    }

//    fun getApiRequest(key: StockKey, urlParam: Map<String, String>, data: Any?= null): ApiRequest {
//        val params = JSONObject(mutableMapOf("request" to urlParam.entries.first().value, "nonce" to "${++key.nonce}"))
//        if (null != data) {
//            when (data) {
//                is JSONArray -> params.put("orders", data)
//                is Map<*, *> -> params.putAll(data)
//            }
//        }
//
//        val payload = Base64.getEncoder().encodeToString(params.toJSONString().toByteArray())
//
//        val mac = Mac.getInstance("HmacSHA384")
//        mac.init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA384"))
//        val sign = String.format("%096x", BigInteger(1, mac.doFinal(payload.toByteArray()))).toLowerCase()
//
//        return stocks.ApiRequest(mapOf("X-BFX-APIKEY" to key.key, "X-BFX-PAYLOAD" to payload, "X-BFX-SIGNATURE" to sign), payload, emptyMap())
//    }

    override fun info(): Map<String, PairInfo>? {
        return parseJsonResponse(SendRequest(privateApi("symbols_details")))?.let {
            (it as List<*>).filter { pairs.containsKey(getRutPair((it as Map<*, *>)["pair"]!!.toString())) }.map {
                val pair = getRutPair((it as Map<*, *>)["pair"]!!.toString())
                pair to PairInfo(pairs[pair]!!.pairId, pairs[pair]!!.stockPairId, BigDecimal(it["minimum_order_size"].toString()))
            }.toMap()
        }
    }

    fun onState(channel: Long, data: JSONArray? = null, update: JSONArray? = null) {
        pairChannel[channel]?.let {
            val pair = it.rutPair
            data?.let {
                val fullState = DepthBook()
                it.forEach {
                    val rate = BigDecimal((it as JSONArray)[0].toString())
                    val amount = BigDecimal((it)[2].toString())
                    val type = if (amount >= BigDecimal.ZERO) BookType.bids else BookType.asks

                    fullState.pairs.getOrPut(pair) { mutableMapOf() }.getOrPut(type) { mutableListOf() }
                            .add(Depth(rate, amount.abs()))
                }
                logger.info("pair: $pair, received ${it.size} asks/bids")
                runBlocking { OnStateUpdate(fullState = fullState) }
            }
            update?.let {
                val rate = BigDecimal(it[1].toString())
                val amount = BigDecimal(it[3].toString())
                val type = if (amount >= BigDecimal.ZERO) BookType.bids else BookType.asks

                val res = runBlocking { OnStateUpdate(listOf(Update(pair, type, rate, if (it[2] == 0L) null else amount.abs()))) }
                if (!res) {
//                    stateSocket.unsubscribe(pair)
                    stateSocket.reconnect()
                }
            }
        }
    }

    fun onWallet(oneCur: List<*>?, multipleCur: List<List<*>>?) = runBlocking {
        val update = mutableMapOf<String, BigDecimal>()

        multipleCur?.let {
            logger.info(it.toString())
            it.filter { it[0] == "exchange" }
                    .map { (it[1] as String).toLowerCase() to BigDecimal(it[2].toString()) }
                    .filter { currencies.containsKey(it.first) }.let { update.putAll(it) }
            onWalletUpdate(update = update)
        }

        oneCur?.let { item ->
            logger.info(item.toString())
            (item[1] as String).toLowerCase().takeIf { currencies.containsKey(it) }?.let {
                update.put(it, BigDecimal(item[2].toString()))
            }
        }
    }
//
//    override suspend fun updateHistory(fromId: Long): Long {
//        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
//    }
//
    fun onOrder(data: List<*>) = runBlocking {
        logger.info(data.toString())
        when (data[13]) {
            "ACTIVE" -> OrderStatus.ACTIVE
            "EXECUTED" -> OrderStatus.COMPLETED
            "CANCELED" -> OrderStatus.CANCELED
            else -> if (data[13].toString().contains("EXECUTED")) OrderStatus.COMPLETED else OrderStatus.PARTIAL
        }.let { onActiveUpdate(OrderUpdate(data[2] as Long, data[0].toString(), null, it)) }
    }
//
////    override fun onMessage(webSocket: WebSocket, text: String) {
////        try {
////            MDC.put("threadId", Thread.currentThread().id.toString())
////            val res = JSONParser().parse(text)
////            when (res) {
////                is JSONArray -> when (res[0]) {
////                    0L -> {
////                        (if (res[1] == "hb" && res.size == 3) 2 else 3).let {
////                            if (!CheckSequence(res[it] as Long)) {
////                                log.error("failed on: $text")
////                            }
////                        }
////
////                        when (res[1]) {
////                            "ws" -> onWallet(null, res[2] as List<List<*>>)
////                            "wu" -> onWallet(res[2] as List<*>, null)
////                            "os" -> (res[2] as List<*>).forEach { onOrder(it as List<*>) }
////                            "on", "ou", "oc" -> onOrder(res[2] as List<*>)
////                            "n" -> OnNotify(res[2] as List<*>)
////                            "te" -> onTradeExecuted(res[2] as List<*>)
////                            "tu" -> onTradeUpdate(res[2] as List<*>)
////                            else -> log.info("not handled: " + text)
////                        }
////                    }
////                    else -> {
////                        CheckSequence(res[2] as Long)
////                        pairChannel[res[0]!! as Long]?.let {
////                            if (ChronoUnit.SECONDS.between(it.time, LocalDateTime.now()) >= 5) {
////                                log.error("heartbeat > 5s for $it")
////                                //restart()
////                                //return
////                                it.time = LocalDateTime.now()
////                            } else {
////                                it.time = LocalDateTime.now()
////                            }
////
////                            when (res[1]) {
////                                is JSONArray -> {
////                                    onState(res[0]!! as Long, res[1]!! as JSONArray)
////                                }
////                                else -> log.info(text)
////                            }
////                        } ?: log.warn("channel not subscribed: ${res[0]!! as Long}")
////                    }
////                }
////                is JSONObject -> when (res["event"]) {
////                    "info" -> log.info("socket version: " + res["version"])
////                    "subscribed" -> {
////                        log.info("subscribed to ${res["pair"]}, channel: ${res["chanId"]}")
////                        val pair = (res["pair"] as String).run { "${dropLast(3)}_${drop(3)}" }.toLowerCase()
////                        pairChannel.put(res["chanId"] as Long, DepthChannel(res["pair"] as String, pair))
////                    }
////                    "unsubscribed" -> {
////                        val chanId = res["chanId"] as Long
////                        val pair = pairChannel[chanId]?.pair
////                        log.info("unsubscribed from channel: ${res["chanId"]}, pair: $pair")
////                        socketState.remove(pairChannel[chanId]?.rutPair)
////                        log.info("pair: $pair, removed from state")
////                        pairChannel.remove(chanId)
////                        Send(JSONObject(mapOf("event" to "subscribe", "channel" to "book", "preq" to "R0", "symbol" to pair, "len" to 100)))
////                    }
////                    "auth" -> {
////                        if (res["status"] == "FAILED") {
////                            log.error(text)
////                            restart()
////                        } else {
////                            pairs.map { it.key.toUpperCase().split("_").joinToString("") }.forEach {
////                                Send(JSONObject(mapOf("event" to "subscribe", "channel" to "book", "preq" to "R0", "symbol" to it, "len" to 100)))
////                            }
////                        }
////                    }
////                    else -> log.info("not handled event: $text")
////                }
////            }
////        } catch (e: Exception) {
////            log.error(e.message, e)
////            log.error("ERRMSG: $text")
////        }
////    }
//
    internal fun onTradeUpdate(data: List<*>) {
        logger.info(data.toString())

        //val pair = data[1].toString().trimStart('t').toLowerCase().let { it.substring(0,3) + "_" + it.substring(3,6) }
        //val order_id = data[3].toString().toLong()
        //val amount = BigDecimal(data[4].toString())
        //val rate = BigDecimal(data[5].toString())
        //val type = if (amount > BigDecimal.ZERO) OrderType.buy else OrderType.sell //TODO: use actual rate
        //[0,"tu",[37060811,"tLTCBTC",1497318672000,2776717677,5.2055456,0.010879,"EXCHANGE LIMIT",0.010879,-1,-0.01041109,"LTC"],849509,2575]
        //onActive(null, order_id, amount.abs())
    }

    internal fun onTradeExecuted(data: List<*>) = runBlocking {
        logger.info(data.toString())

//        val pair = data[1].toString().trimStart('t').toLowerCase().let { it.substring(0,3) + "_" + it.substring(3,6) }
        val order_id = data[3].toString()
        val amount = BigDecimal(data[4].toString())
//        val rate = BigDecimal(data[5].toString())
//        val type = if (amount > BigDecimal.ZERO) OrderType.buy else OrderType.sell //TODO: use actual rate

        onActiveUpdate(OrderUpdate(null, order_id, amount.abs(), null))
    }

    internal fun OnNotify(data: List<*>) = runBlocking<Unit> {
        logger.info(data.toString())

        when (data[1].toString()) {
            "on-req" -> {
                val orderData = data[4] as List<*>
                val deal_id = orderData[2].toString().toLong()
                when (data[6].toString()) {
                    "ERROR" -> {
                        var order: List<Order>? = null
//                        arbModule.stateLock.write {
                        runBlocking {
                            RutEx.stateLock.withLock {
                                activeList.find { it.id == deal_id }?.let {
                                    if (data[7].toString().contains("Invalid order: not enough exchange balance") && it.attempt < 200) {
                                        logger.info("trying to resend, attempt ${it.attempt}, id: ${it.id}")
                                        it.id = Math.abs(UUID.randomUUID().hashCode()).toLong()
                                        logger.info("new id: ${it.id}")
                                        order = listOf(it.apply { attempt++ })
                                    } else {
                                        onActiveUpdate(OrderUpdate(deal_id, "666", BigDecimal.ZERO, OrderStatus.FAILED))
                                    }
                                } ?: logger.error("order $deal_id not found")
                            }
                        }
                        order?.let { putOrders(it) }
                    }
                    "SUCCESS" -> {
                        val order_id = orderData[0].toString()
                        //val amount = BigDecimal(orderData[6].toString()).abs()
                        onActiveUpdate(OrderUpdate(deal_id, order_id, null, null))
                    }
                }
            }
            "deposit_new" -> {
            }
            "deposit_complete" -> {
                if (data[6].toString() == "SUCCESS") {
                    val (_, cur) = data[7].toString().substringAfter("Deposit completed: ").toLowerCase().split(" ")
                    cur.replace("dash", "dsh")
                    updateDeposit(cur)
//                    state.onWalletUpdate(plus = Pair(cur, BigDecimal(amount)))
                }

                //2017-06-20 17:32:57,637 INFO  Bitfinex.OnNotify [null,"deposit_complete",null,null,null,null,"SUCCESS","Deposit completed: 39.999 LTC"]
            }
        }
    }
//
//    override suspend fun getOrderInfo(order: Order, updateTotal: Boolean) {
//        ParseResponse(state.SendRequest(orderUrl.keys.first(), getApiRequest(state.getActiveKey(), orderUrl, mapOf("order_id" to order.order_id))))?.also {
//            val res = it as Map<*, *>
//            val remaining = BigDecimal(res["remaining_amount"].toString())
//            val status = if (res["is_live"].toString().toBoolean()) {
//                if (order.amount > remaining) OrderStatus.PARTIAL else OrderStatus.ACTIVE
//            } else {
//                if (res["is_cancelled"].toString().toBoolean()) OrderStatus.CANCELED else OrderStatus.COMPLETED
//            }
//
//            order.takeIf { it.status != status || it.remaining.compareTo(remaining) != 0 }
//                    ?.let { state.onActive(it.id, it.order_id, it.remaining - remaining, status, updateTotal) }
//        } ?: state.log.error("OrderInfo failed: $order")
//    }
//
//    fun ParseResponse(response: String?): Any? {
//        response?.let {
//            val obj = JSONParser().parse(it)
//            if (obj is JSONObject) {
//                if (obj.containsKey("message")) {
//                    state.log.error(obj["message"].toString())
//                    return null
//                }
//                return obj
//            } else if (obj is JSONArray) {
//                return obj
//            } else {
//                state.log.error("unknown")
//                return null
//            }
//        }
//        return null
//    }

    override suspend fun putOrders(orders: List<Order>) = parallelOrders(orders) { order ->
        val data = JSONArray().apply {
            addAll(listOf(0, "on", null, JSONObject(mapOf(
                    "cid" to order.id,
                    "type" to "EXCHANGE LIMIT",
                    "symbol" to order.pair.toUpperCase().split("_").joinToString("", "t"),
                    "amount" to order.run { if (type == "sell") amount.negate() else amount }.toString(),
                    "price" to order.rate.toString(),
                    "hidden" to 0))))
        }

        logger.info("send to play $data")

        if (!controlSocket.socket.send(data.toJSONString())) {
            logger.error("send failed")
            OrderUpdate(order.id, "666", BigDecimal.ZERO, OrderStatus.FAILED)
        } else {
            OrderUpdate()
        }
    }

    override suspend fun start() {
//        runBlocking { state.activeList.toList().forEach { getOrderInfo(it, false) } }
//        state.log.info("active list updated")

//        coroutines.addAll(listOf(debugWallet(state.debugWallet), info(this::info, 5, state.name, state.pairs, arbModule.stateLock)))
        syncWallet()

        logger.info("start websockets")
        pairChannel.clear()
        stateSocket = BitfinexPublicSocket(this).also { it.start() }
        controlSocket = BitfinexPrivateSocket(this, historyKey).also { it.start() }
        coroutines.addAll(listOf(infoPolling(), debugWallet()))
        coroutines.forEach { it.join() }
    }

    override suspend fun stop() {
        logger.info("closing socket..")
        stateSocket.stop()
        controlSocket.stop()
        shutdown()
    }

    override fun withdraw(transfer: Transfer, key: StockKey): Pair<TransferStatus, String> {
        val data = mapOf("amount" to transfer.amount.toPlainString(), "withdraw_type" to withdrawType[transfer.cur]!!,
                "walletselected" to "exchange", "address" to transfer.address.first)

        return apiRequest("withdraw", key, data)?.let {
            val res = ((it as List<*>).first() as Map<*, *>)
            if (res["status"] == "success") {
                Pair(TransferStatus.PENDING, res["withdrawal_id"].toString())
            } else {
                Pair(TransferStatus.FAILED, "")
            }
        } ?: Pair(TransferStatus.FAILED, "")

    }

    companion object {
        val name = Bitfinex::class.simpleName!!
        val Pairs = mapOf(
            P(C.bab, C.btc) to "",
            P(C.bab, C.usd) to "",

//                P(C.bch, C.btc) to "",
//                P(C.bch, C.eth) to "",
//                P(C.bch, C.usd) to "",

            P(C.bsv, C.btc) to "",
            P(C.bsv, C.usd) to "",

            P(C.btc, C.eur) to "",
            P(C.btc, C.gbp) to "",
            P(C.btc, C.jpy) to "",
            P(C.btc, C.usd) to "",

            P(C.btg, C.btc) to "",
            P(C.btg, C.usd) to "",

            P(C.dsh, C.btc) to "",
            P(C.dsh, C.usd) to "",

            P(C.eos, C.btc) to "",
            P(C.eos, C.eur) to "",
            P(C.eos, C.eth) to "",
            P(C.eos, C.gbp) to "",
            P(C.eos, C.jpy) to "",
            P(C.eos, C.usd) to "",

            P(C.etc, C.btc) to "",
            P(C.etc, C.usd) to "",

            P(C.eth, C.btc) to "",
            P(C.eth, C.eur) to "",
            P(C.eth, C.gbp) to "",
            P(C.eth, C.jpy) to "",
            P(C.eth, C.usd) to "",

            P(C.ltc, C.btc) to "",
            P(C.ltc, C.usd) to "",

            P(C.omg, C.btc) to "",
            P(C.omg, C.eth) to "",
            P(C.omg, C.usd) to "",

            P(C.xmr, C.btc) to "",
            P(C.xmr, C.usd) to "",

            P(C.xrp, C.btc) to "",
            P(C.xrp, C.usd) to "",

            P(C.zec, C.btc) to "",
            P(C.zec, C.usd) to ""
        )
    }
}

