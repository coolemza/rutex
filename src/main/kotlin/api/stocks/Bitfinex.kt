package api.stocks

import api.OrderUpdate
import api.Transfer
import api.TransferUpdate
import api.Update
import bot.IWebSocket
import data.*
import database.*
import database.RutData.P
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.kodein.di.Kodein
import stocks.WebSocketStock
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Bitfinex(kodein: Kodein): WebSocketStock(kodein, name) {

    data class DepthChannel(val pair: String, val rutPair: String, var time: LocalDateTime = LocalDateTime.now())

    private val pairChannel = mutableMapOf<Long, DepthChannel>()

    override val tradeAttemptCount = 30

    private fun privateApi(cmd: String, version: Int = 1) = Pair("https://api.bitfinex.com/v$version/$cmd", "/v1/$cmd")

    override suspend fun cancelOrders(orders: List<Order>) {
        val data = JSONArray().apply {
            addAll(listOf(0, "oc_multi", null, JSONObject(mapOf("id" to JSONArray().apply { addAll(orders.map { it.stockOrderId.toLong() }) }))))
        }

        logger.info("send to cancel $data")
        if (!controlSocket.send(data.toJSONString())) {
            logger.error("Orders cancelling failed")
            orders.forEach { updateActive(OrderUpdate(it.id, it.stockOrderId, it.amount, OrderStatus.CANCEL_FAILED), false) }
        }
    }

    override suspend fun deposit(lastId: Long, transfers: List<Transfer>): Pair<Long, List<TransferUpdate>> {

        val tu = transfers.map { transfer ->
            logger.info("txId(${transfer.tId}) status ${transfer.status}")
            var status = transfer.status
            apiRequest("history/movements", mapOf("method" to getWithdrawSymbol(transfer.cur)), timeOut = 10000)?.let { res ->
                (res as List<*>).find { (it as Map<*,*>)["txid"].toString() == transfer.tId }.let { (it as Map<*, *>) }.let {
                    logger.info("txId(${it["txid"]}) found status ${it["status"]}")
                    status = when (it["status"].toString()) {
                        "Success" -> TransferStatus.SUCCESS
                        "Failed" -> TransferStatus.FAILED
                        else -> transfer.status
                    }
                }
            }

            TransferUpdate(transfer.id, status)
        }
        return Pair(lastId, tu)
    }

    override fun handleError(res: Any): Any? {
        return res.takeIf { it is JSONArray || !(it as JSONObject).containsKey("message") }
            ?: throw Exception((res as JSONObject)["message"].toString())
    }

    override suspend fun balance(): Map<String, BigDecimal>? = apiRequest("balances")?.let { res ->
        (res as List<*>).filter { (it as Map<*, *>)["type"] == "exchange" }
            .map { (it as Map<*, *>)["currency"] as String to BigDecimal((it)["available"] as String) }
            .filter { currencies.containsKey(it.first) }.toMap()
    }

    suspend fun apiRequest(cmd: String, data: Map<String, Any>? = null, key: StockKey? = infoKey, timeOut: Long = 2000): Any? {
        key?.let {
            val params = JSONObject(mutableMapOf("request" to privateApi(cmd).second, "nonce" to "${++key.nonce}"))
            if (null != data) {
                when (data) {
                    is JSONArray -> params["orders"] = data
                    else -> params.putAll(data)
                }
            }

            val payload = Base64.getEncoder().encodeToString(params.toJSONString().toByteArray())

            val mac = Mac.getInstance("HmacSHA384")
            mac.init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA384"))
            val sign = String.format("%096x", BigInteger(1, mac.doFinal(payload.toByteArray()))).toLowerCase()

            return parseJsonResponse(
                http.post(
                    logger,
                    privateApi(cmd).first,
                    mapOf("X-BFX-APIKEY" to key.key, "X-BFX-PAYLOAD" to payload, "X-BFX-SIGNATURE" to sign),
                    payload
                )
            )
        }
    }

    override suspend fun currencyInfo() = apiRequest("account_fees")?.let { res ->
        ((res as Map<*, *>)["withdraw"] as Map<*, *>)
            .map { it.key as String to CrossFee(withdrawFee = Fee(min = BigDecimal(it.value.toString()))) }
            .filter { currencies.containsKey(rutSymbol(it.first)) }
            .map { rutSymbol(it.first) to it.second }.toMap()
    }

    override suspend fun pairInfo() = apiRequest("account_infos")?.let { res ->
        val (makerFee, takerFee) = ((res as List<*>)[0] as Map<*, *>).let { Pair(it["maker_fees"], it["taker_fees"]) }

        parseJsonResponse(http.get(logger, privateApi("symbols_details").first))?.let { sd ->
            (sd as List<*>).filter { pairs.containsKey(getRutPair((it as Map<*, *>)["pair"]!!.toString())) }.map {
                val pair = getRutPair((it as Map<*, *>)["pair"]!!.toString())
                pair to TradeFee(BigDecimal(it["minimum_order_size"].toString()), BigDecimal(makerFee.toString()),
                    BigDecimal(takerFee.toString()))
            }.toMap()
        }
    }

    private fun onState(channel: Long, data: JSONArray? = null, update: JSONArray? = null) {
        val pair = pairChannel[channel]!!.rutPair
        data?.let { fullUpdate ->
            val fullState = DepthBook()
            fullUpdate.forEach {
                val rate = BigDecimal((it as JSONArray)[0].toString())
                val amount = BigDecimal((it)[2].toString())
                val type = if (amount >= BigDecimal.ZERO) BookType.bids else BookType.asks

                fullState.getOrPut(pair) { DepthType() }.getOrPut(type) { DepthList() }
                    .add(Depth(rate, amount.abs()))
            }
            logger.info("pair: $pair, received ${fullUpdate.size} asks/bids")
            updateActor.offer(InitPair(pair, fullState))
        }

        update?.let { (it[1] as JSONArray) }?.let {
            val rate = BigDecimal(it[0].toString())
            val amount = BigDecimal(it[2].toString())
            val type = if (amount >= BigDecimal.ZERO) BookType.bids else BookType.asks

            logger.trace("$update")
            updateActor.offer(SingleUpdate(Update(pair, type, rate, if (it[1] == 0L) null else amount.abs())))
        }
    }

    private fun onWallet(oneCur: List<*>? = null, multipleCur: List<*>? = null) {
        val update = mutableMapOf<String, BigDecimal>()

        multipleCur?.let { list ->
            logger.info(list.toString())
            list.filter { (it as List<*>)[0] == "exchange" }
                .map { ((it as List<*>)[1] as String).toLowerCase() to BigDecimal(it[2].toString()) }
                .filter { currencies.containsKey(it.first) }.let { update.putAll(it) }
            updateWallet(UpdateWallet(name, update = update))
        }

        oneCur?.let { item ->
            logger.info(item.toString())
            (item[1] as String).toLowerCase().takeIf { currencies.containsKey(it) }?.let {
                update.put(it, BigDecimal(item[2].toString()))
            }
        }
    }

    private fun onOrder(data: List<*>) {
        logger.info(data.toString())
        updateActive(
            OrderUpdate(data[2] as Long, data[0].toString(), null, when (data[13]) {
            "ACTIVE" -> OrderStatus.ACTIVE
            "EXECUTED" -> OrderStatus.COMPLETED
            "CANCELED" -> OrderStatus.CANCELED
            else -> if (data[13].toString().contains("EXECUTED")) OrderStatus.COMPLETED else OrderStatus.PARTIAL
        }), false)
    }

    /**
     * Last notification about trade
     *
     * val pair = data[1].toString().trimStart('t').toLowerCase().let { it.substring(0,3) + "_" + it.substring(3,6) }
     * val order_id = data[3].toString().toLong()
     * val amount = BigDecimal(data[4].toString())
     * val rate = BigDecimal(data[5].toString())
     */
    private fun onTradeUpdate(data: List<*>) {
        logger.info(data.toString())
        updateActive(OrderUpdate(orderId = data[3].toString(), amount = BigDecimal(data[4].toString()).abs()), false)
    }

    /**
     * first notification about trade
     *
     * val pair = data[1].toString().trimStart('t').toLowerCase().let { it.substring(0,3) + "_" + it.substring(3,6) }
     * val order_id = data[3].toString().toLong()
     * val amount = BigDecimal(data[4].toString())
     * val rate = BigDecimal(data[5].toString())
     */
    private fun onTradeExecuted(data: List<*>) {
        logger.info(data.toString())
//        updateActive(OrderUpdate(orderId = data[3].toString(), amount = BigDecimal(data[4].toString()).abs()), false)
    }

    private fun onNotify(data: List<*>) {
        logger.info(data.toString())

        when (data[1].toString()) {
            "on-req" -> {
                val orderData = data[4] as List<*>
                val dealId = orderData[2].toString().toLong()
                when (data[6].toString()) {
                    "ERROR" -> updateActive(OrderUpdate(id = dealId, status = OrderStatus.FAILED), false)
                    "SUCCESS" -> updateActive(OrderUpdate(id = dealId, orderId = orderData[0].toString()), true)
                }
            }
            "deposit_new" -> { }
            "deposit_complete" -> {
                if (data[6].toString() == "SUCCESS") {
                    val (_, cur) = data[7].toString().substringAfter("Deposit completed: ").split(" ")
                    updateDeposit(rutSymbol(cur))
                }
                //2017-06-20 17:32:57,637 INFO  Bitfinex.onNotify [null,"deposit_complete",null,null,null,null,"SUCCESS","Deposit completed: 39.999 LTC"]
            }
        }
    }

    override suspend fun orderInfo(order: Order, updateTotal: Boolean) =
        apiRequest("order/status", mapOf("order_id" to order.stockOrderId.toLong()), infoKey)?.let {
            val res = it as Map<*, *>
            val remaining = BigDecimal(res["remaining_amount"].toString())
            val status = if (res["is_live"].toString().toBoolean()) {
                if (order.amount > remaining) OrderStatus.PARTIAL else OrderStatus.ACTIVE
            } else {
                if (res["is_cancelled"].toString().toBoolean()) OrderStatus.CANCELED else OrderStatus.COMPLETED
            }

            OrderUpdate(order.id, order.stockOrderId, order.remaining - remaining, status)
        } ?: logger.error("OrderInfo response failed: $order").let { null }

    override suspend fun putOrders(orders: List<Order>) {
        val data = JSONArray().apply {
            addAll(listOf(0, "ox_multi", null, JSONArray().apply { addAll(orders.map { order ->
                JSONArray().apply { addAll(listOf("on", JSONObject(mapOf(
                    "cid" to order.id,
                    "type" to "EXCHANGE LIMIT",
                    "symbol" to order.pair.toUpperCase().split("_").joinToString("", "t"),
                    "amount" to order.run { if (type == OperationType.sell) amount.negate() else amount }.toString(),
                    "price" to order.rate.toString(),
                    "hidden" to 0)))) }
            }.toList()) }))
        }

        logger.info("send to play $data")

        if (!controlSocket.send(data.toJSONString())) {
            logger.error("send failed")
            orders.forEach { OrderUpdate(it.id, "666", BigDecimal.ZERO, OrderStatus.FAILED) }
        }
    }

    override suspend fun onMessage(str: String) {
        try {
            JSONParser().parse(str).let { res ->
                when (res) {
                    is JSONArray -> when (res[0]) {
                        0L -> logger.info("what?? - $str")
                        else -> {
                            pairChannel[res[0] as Long]?.let {
                                val timeOut = ChronoUnit.SECONDS.between(it.time, LocalDateTime.now())
                                when (timeOut > 10) {
                                    true -> logger.warn("heartbeat - ${timeOut}s for $it").run { it.time = LocalDateTime.now() }
                                    else -> it.time = LocalDateTime.now()
                                }

                                when (res[1]) {
                                    "hb" -> logger.trace(str)
                                    is JSONArray -> when ((res[1] as JSONArray).size) {
                                        3 -> onState(res[0] as Long, update = res)
                                        else -> onState(res[0] as Long, data = res[1] as JSONArray)
                                    }
                                }
                            } ?: logger.warn("channel not subscribed: ${res[0] as Long}")
                        }
                    }
                    is JSONObject -> when (res["event"]) {
                        "info" -> logger.info("socket version: " + res["version"])
                        "subscribed" -> {
                            logger.info("subscribed to ${res["pair"]}, channel: ${res["chanId"]}")
                            val pair = (res["pair"] as String).run { "${dropLast(3)}_${drop(3)}" }.toLowerCase()
                            pairChannel.put(res["chanId"] as Long, DepthChannel(res["pair"] as String, pair))
                        }
                        "unsubscribed" -> {
                            val chanId = res["chanId"] as Long
                            val pair = pairChannel[chanId]?.pair
                            logger.info("unsubscribed from channel: ${res["chanId"]}, pair: $pair")
//                            depthBook.remove(pairChannel[chanId]?.rutPair)
                            remove(pairChannel[chanId]!!.rutPair)
                            logger.info("pair: $pair, removed from state")
                            pairChannel.remove(chanId)
                            bookSocket.send(JSONObject(mapOf("event" to "subscribe", "channel" to "book", "preq" to "R0", "symbol" to pair, "len" to 100)).toJSONString())
                        }
                        else -> logger.info("not handled event: $str")
                    }
                    else -> { }
                }
            }
        } catch (e: Exception) {
            logger.error(e.message, e)
            logger.error("MSG: $str")
        }
    }

    override suspend fun onPrivateMessage(str: String) {
        try {
            when (val res = JSONParser().parse(str)) {
                is JSONArray -> when (res[0]) {
                    0L -> {
//                        (if (res[1] == "hb" && res.size == 3) 2 else 3).let {
//                            if (!CheckSequence(res[it] as Long)) {
//                                stock.logger.error("failed on: $text")
//                                reconnect()
//                            }
//                        }

                        when (res[1]) {
                            "ws" -> onWallet(multipleCur = (res[2] as List<*>))
                            "wu" ->  onWallet(oneCur = (res[2] as List<*>))
                            "os" -> (res[2] as List<*>).forEach { onOrder(it as List<*>) }
                            "on", "ou", "oc" -> onOrder(res[2] as List<*>)
                            "n" -> onNotify(res[2] as List<*>)
                            "te" -> onTradeExecuted(res[2] as List<*>)
                            "tu" -> onTradeUpdate(res[2] as List<*>)
                            "hb" -> logger.trace(str)
                            else -> logger.info("not handled: $str")
                        }
                    }
                    else -> {
                        logger.error("wrong channel - $str")
                    }
                }
                is JSONObject -> when (res["event"]) {
                    "info" -> logger.info("socket version: " + res["version"])
                    "auth" -> {
                        if (res["status"] == "FAILED") {
                            logger.error(str)
                            reconnectControlSocket()
                        }
                    }
                    else -> logger.info("not handled event: $str")
                }
            }
        } catch (e: Exception) {
            logger.error(e.message, e)
            logger.error("MSG: $str")
        }
    }

    override suspend fun loginPrivateSocket(socket: IWebSocket) {
        activeKey?.let {
            socket.send(JSONObject(mapOf("event" to "conf", "flags" to 65536)).toJSONString())

            val nonce = Instant.ofEpochSecond(0L).until(Instant.now(), ChronoUnit.SECONDS).toString()
            val payload = "AUTH$nonce"
            val sign = Mac.getInstance("HmacSHA384")
                .apply { init(SecretKeySpec(it.secret.toByteArray(), "HmacSHA384")) }
                .doFinal(payload.toByteArray())

            val cmd = JSONObject(
                mapOf(
                    "apiKey" to it.key,
                    "event" to "auth",
                    "authPayload" to payload,
                    "authNonce" to nonce,
                    "authSig" to String.format("%X", BigInteger(1, sign)).toLowerCase()
                )
            )

            socket.send(cmd.toJSONString())
        }
    }

    override suspend fun loginBookSocket(socket: IWebSocket) {
        pairChannel.clear()
        pairs.map { it.key.toUpperCase().split("_").joinToString("") }.forEach {
            logger.trace { "subscribing $it" }
            socket.send(JSONObject(mapOf("event" to "subscribe", "channel" to "book", "preq" to "R0", "symbol" to it, "len" to 25)).toJSONString())
        }
    }

    override suspend fun start() {
        logger.info("start websocket's")
        startControlSocket("api.bitfinex.com", "/ws/2")
        startBookSocket("api.bitfinex.com", "/ws/2")
    }

    override suspend fun withdraw(transfer: Transfer): Pair<TransferStatus, String> {
        val data = mapOf("amount" to transfer.amount.toPlainString(), "withdraw_type" to getWithdrawSymbol(transfer.cur),
            "walletselected" to "exchange", "address" to transfer.address.first)

        return apiRequest("withdraw", data, withdrawKey)?.let {
            val res = ((it as List<*>).first() as Map<*, *>)
            if (res["status"] == "success") {
                Pair(TransferStatus.PENDING, res["withdrawal_id"].toString())
            } else {
                Pair(TransferStatus.FAILED, "")
            }
        } ?: Pair(TransferStatus.FAILED, "")

    }

    private fun rutSymbol(cur: String): String {
        return when (cur) {
            "DASH" -> C.dsh.name
            else -> cur.toLowerCase()
        }.toString()
    }

    private fun getWithdrawSymbol(cur: String): String {
        return when(cur) {
            "btc" -> "bitcoin"
            "ltc" -> "litecoin"
            "eth" -> "ethereum"
            else -> throw Exception("not defined withdraw currency")
        }
        //    Withdrawal Types
        //    "bitcoin", "litecoin", "ethereum", "ethereumc", "tetheruso", "zcash", "monero", "iota", "ripple", "dash",
        // "adjustment", "wire", "eos", "santiment", "omisego", "bcash", "neo", "metaverse", "qtum", "aventus", "eidoo",
        // "datacoin", "tetheruse", "bgold", "qash", "yoyow", "golem", "status", "tethereue", "bat", "mna", "fun", "zrx",
        // "tnb", "spk", "trx", "rcn", "rlc", "aid", "sng", "rep", "elf"
    }

    private fun getRutPair(pair: String) = pair.toLowerCase().let { it.substring(0..2) + "_" + it.substring(3..5) }

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

