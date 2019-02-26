package api.stocks

import api.*
import api.connectors.*
import data.*
import database.*
import database.RutData.P
import kotlinx.coroutines.ObsoleteCoroutinesApi
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.kodein.di.Kodein
import utils.DepthChannel
import utils.IWebSocket
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ObsoleteCoroutinesApi
@ExperimentalUnsignedTypes
class Bitfinex(kodein: Kodein): Stock(kodein, name) {
    override suspend fun reconnectPair(pair: String) = bookConnector.reconnect()

    val bookConnector = WebSocketBookConnector("api.bitfinex.com", "/ws/2", kodein, logger, { msg, conn -> onBookMessage(msg, conn) })
    {
        pairs.map { it.key.toUpperCase().split("_").joinToString("") }.forEach { pair ->
            logger.trace { "subscribing $pair" }
            it.send(
                JSONObject(
                    mapOf("event" to "subscribe", "channel" to "book", "preq" to "R0", "symbol" to pair, "len" to 25)
                ).toJSONString()
            )
        }
    }

    val controlConnector = WebSocketControlConnector("api.bitfinex.com", "/ws/2", kodein, logger,
        { loginPrivateSocket(it) }, { onControlMessage(it) })

    private val restConnector = RestControlConnector(emptyList(), kodein, logger, { _, _ -> })
    { urlParam, key, data -> auth(urlParam, key, data) }

    override val tradeAttemptCount = 30

    private fun restApi(cmd: String, version: Int = 1) = Pair("https://api.bitfinex.com/v$version/$cmd", "/v1/$cmd")

    private suspend fun api(cmd: String, key: StockKey?, data: Map<String, Any>? = null, timeOut: Long = 2000): Any? {
        return parseJsonResponse(restConnector.apiRequest(restApi(cmd), key, data, timeOut))
    }

    private fun auth(urlParam: String, key: StockKey, data: Map<String, Any>?): Pair<Map<String, String>, String> {
        val params = JSONObject(mutableMapOf("request" to urlParam, "nonce" to "${++key.nonce}"))
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

        return Pair(mapOf("X-BFX-APIKEY" to key.key, "X-BFX-PAYLOAD" to payload, "X-BFX-SIGNATURE" to sign), payload)
    }

    override suspend fun cancelOrders(orders: List<Order>) {
        val data = JSONArray().apply {
            addAll(listOf(0, "oc_multi", null, mapOf("id" to orders.map { it.stockOrderId.toLong() })))
        }

        logger.info("send to cancel $data")
        if (!controlConnector.send(data.toJSONString())) {
            logger.error("Orders cancelling failed")
            orders.forEach {
                updateActive(
                    OrderUpdate(it.id, it.stockOrderId, it.amount, OrderStatus.CANCEL_FAILED),
                    false
                )
            }
        }
    }

    override suspend fun deposit(lastId: Long, transfers: List<Transfer>, key: StockKey?): Pair<Long, List<TransferUpdate>> {

        val tu = transfers.map { transfer ->
            logger.info("txId(${transfer.tId}) status ${transfer.status}")
            var status = transfer.status
            api("history/movements", key, mapOf("method" to getWithdrawSymbol(transfer.cur)), 10000)?.let { res ->
                (res as List<*>).find { (it as Map<*, *>)["txid"].toString() == transfer.tId }.let { (it as Map<*, *>) }
                    .let {
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

    override suspend fun balance(key: StockKey?): Map<String, BigDecimal>? = api("balances", key)?.let { res ->
        (res as List<*>).filter { (it as Map<*, *>)["type"] == "exchange" }
            .map { (it as Map<*, *>)["currency"] as String to BigDecimal((it)["available"] as String) }
            .filter { currencies.containsKey(it.first) }.toMap()
    }

//    override suspend fun apiRequest(cmd: String, key: StockKey, data: Map<String, Any>?, timeOut: Long): Any? {
//        val params = JSONObject(mutableMapOf("request" to privateApi(cmd).second, "nonce" to "${++key.nonce}"))
//        if (null != data) {
//            when (data) {
//                is JSONArray -> params["orders"] = data
//                else -> params.putAll(data)
//            }
//        }
//
//        val payload = Base64.getEncoder().encodeToString(params.toJSONString().toByteArray())
//
//        val mac = Mac.getInstance("HmacSHA384")
//        mac.init(SecretKeySpec(key.secret.toByteArray(), "HmacSHA384"))
//        val sign = String.format("%096x", BigInteger(1, mac.doFinal(payload.toByteArray()))).toLowerCase()
//
//        return parseJsonResponse(http.post(logger, privateApi(cmd).first, mapOf("X-BFX-APIKEY" to key.key, "X-BFX-PAYLOAD" to payload, "X-BFX-SIGNATURE" to sign), payload))
//    }

    override suspend fun currencyInfo(key: StockKey?) = api("account_fees", key)?.let { res ->
        ((res as Map<*, *>)["withdraw"] as Map<*, *>)
            .map { it.key as String to CrossFee(withdrawFee = Fee(min = BigDecimal(it.value.toString()))) }
            .filter { currencies.containsKey(rutSymbol(it.first)) }
            .map { rutSymbol(it.first) to it.second }.toMap()
    }

    override suspend fun pairInfo(key: StockKey?) = api("account_infos", key)?.let { res ->
        val (makerFee, takerFee) = ((res as List<*>)[0] as Map<*, *>).let { Pair(it["maker_fees"], it["taker_fees"]) }

        parseJsonResponse(restConnector.http.get(logger, restApi("symbols_details").first))?.let { sd ->
            (sd as List<*>).filter { pairs.containsKey(getRutPair((it as Map<*, *>)["pair"]!!.toString())) }.map {
                val pair = getRutPair((it as Map<*, *>)["pair"]!!.toString())
                pair to TradeFee(
                    BigDecimal(it["minimum_order_size"].toString()), BigDecimal(makerFee.toString()),
                    BigDecimal(takerFee.toString())
                )
            }.toMap()
        }
    }

    private fun onState(pair: String, data: JSONArray? = null, update: JSONArray? = null) {
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
            initPair(pair, fullState)
        }

        update?.let { (it[1] as JSONArray) }?.let {
            val rate = BigDecimal(it[0].toString())
            val amount = BigDecimal(it[2].toString())
            val type = if (amount >= BigDecimal.ZERO) BookType.bids else BookType.asks

            logger.trace("$update")
            singleUpdate(Update(pair, type, rate, if (it[1] == 0L) null else amount.abs()))
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
            OrderUpdate(
                data[2] as Long, data[0].toString(), null, when (data[13]) {
                    "ACTIVE" -> OrderStatus.ACTIVE
                    "EXECUTED" -> OrderStatus.COMPLETED
                    "CANCELED" -> OrderStatus.CANCELED
                    else -> if (data[13].toString().contains("EXECUTED")) OrderStatus.COMPLETED else OrderStatus.PARTIAL
                }
            ), false
        )
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
            "deposit_new" -> {
            }
            "deposit_complete" -> {
                if (data[6].toString() == "SUCCESS") {
                    val (_, cur) = data[7].toString().substringAfter("Deposit completed: ").split(" ")
                    updateDeposit(rutSymbol(cur))
                }
                //2017-06-20 17:32:57,637 INFO  Bitfinex.onNotify [null,"deposit_complete",null,null,null,null,"SUCCESS","Deposit completed: 39.999 LTC"]
            }
        }
    }

    override suspend fun orderInfo(order: Order, updateTotal: Boolean): OrderUpdate? {
        return api("order/status", infoKey, mapOf("order_id" to order.stockOrderId.toLong()))?.let {
            val res = it as Map<*, *>
            val remaining = BigDecimal(res["remaining_amount"].toString())
            val status = if (res["is_live"].toString().toBoolean()) {
                if (order.amount > remaining) OrderStatus.PARTIAL else OrderStatus.ACTIVE
            } else {
                if (res["is_cancelled"].toString().toBoolean()) OrderStatus.CANCELED else OrderStatus.COMPLETED
            }

            OrderUpdate(order.id, order.stockOrderId, order.remaining - remaining, status)
        } ?: logger.error("OrderInfo response failed: $order").run { null }
    }

    private fun onBookMessage(str: String, connector: WebSocketBookConnector) {
        try {
            JSONParser().parse(str).let { res ->
                when (res) {
                    is JSONArray -> when (res[0]) {
                        0L -> logger.info("what?? - $str")
                        else -> {
                            connector.pairChannel[res[0] as Long]?.let {
                                val timeOut = ChronoUnit.SECONDS.between(it.time, LocalDateTime.now())
                                when (timeOut > 10) {
                                    true -> logger.warn("heartbeat - ${timeOut}s for $it").run { it.time = LocalDateTime.now() }
                                    else -> it.time = LocalDateTime.now()
                                }

                                when (res[1]) {
                                    "hb" -> logger.trace(str)
                                    is JSONArray -> when ((res[1] as JSONArray).size) {
                                        3 -> onState(connector.pairChannel[res[0] as Long]!!.rutPair, update = res)
                                        else -> onState(connector.pairChannel[res[0] as Long]!!.rutPair, data = res[1] as JSONArray)
                                    }
                                }
                            } ?: logger.warn("channel not subscribed: ${res[0] as Long}")
                        }
                    }
                    is JSONObject -> when (res["event"]) {
                        "info" -> logger.info("socket version: " + res["version"])
                        "subscribed" -> {
//                            logger.info("subscribed to ${res["pair"]}, channel: ${res["chanId"]}")
                            val pair = (res["pair"] as String).run { "${dropLast(3)}_${drop(3)}" }.toLowerCase()
                            connector.pairChannel[res["chanId"] as Long] = DepthChannel(res["pair"] as String, pair)
                        }
                        "unsubscribed" -> {
                            val chanId = res["chanId"] as Long
                            val pair = connector.pairChannel[chanId]?.pair
                            logger.info("unsubscribed from channel: ${res["chanId"]}, pair: $pair")
                            pairError(connector.pairChannel[chanId]!!.rutPair)
                            logger.info("pair: $pair, removed from state")
                            connector.pairChannel.remove(chanId)
                            bookConnector.send(
                                JSONObject(mapOf("event" to "subscribe", "channel" to "book",
                                    "preq" to "R0", "symbol" to pair, "len" to 100)).toJSONString()
                            )
                        }
                        else -> logger.info("not handled event: $str")
                    }
                    else -> { }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "MSG: $str" }
        }
    }

    private suspend fun onControlMessage(str: String) {
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
                            controlConnector.reconnect()
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

        if (!controlConnector.send(data.toJSONString())) {
            logger.error("send failed")
            orders.forEach { OrderUpdate(it.id, "666", BigDecimal.ZERO, OrderStatus.FAILED) }
        }
    }

    private fun loginPrivateSocket(socket: IWebSocket) {
        socket.send(JSONObject(mapOf("event" to "conf", "flags" to 65536)).toJSONString())

        val nonce = Instant.ofEpochSecond(0L).until(Instant.now(), ChronoUnit.SECONDS).toString()
        val payload = "AUTH$nonce"
        val sign = Mac.getInstance("HmacSHA384")
            .apply { init(SecretKeySpec(walletKey?.secret?.toByteArray(), "HmacSHA384")) }
            .doFinal(payload.toByteArray())

        val cmd = JSONObject(mapOf("apiKey" to walletKey?.key,
            "event" to "auth",
            "authPayload" to payload,
            "authNonce" to nonce,
            "authSig" to String.format("%X", BigInteger(1, sign)).toLowerCase()))

        socket.send(cmd.toJSONString())
    }

    override suspend fun start() {
        super.start()
        listOf<IConnector>(restConnector, controlConnector, bookConnector).forEach { it.start() }
    }

    override suspend fun stop() {
        listOf<IConnector>(restConnector, controlConnector, bookConnector).forEach { it.stop() }
        super.stop()
    }

    override suspend fun withdraw(transfer: Transfer): Pair<TransferStatus, String> {
        val data = mapOf("amount" to transfer.amount.toPlainString(), "withdraw_type" to getWithdrawSymbol(transfer.cur),
            "walletselected" to "exchange", "address" to transfer.address.first)

        return api("withdraw", withdrawKey, data)?.let {
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

