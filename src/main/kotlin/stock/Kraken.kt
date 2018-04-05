package stock

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import data.Depth
import data.DepthBook
import data.Order
import database.*
import kotlinx.coroutines.experimental.Deferred
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import org.jsoup.Jsoup
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.Executors
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.collections.HashMap

class Kraken(override val kodein: Kodein) : IStock, KodeinAware {
    override val state = State(this::class.simpleName!!, kodein)
    private var tm = TradeManager(state)
    private val statePool = Executors.newScheduledThreadPool(1)
    private val coroutines = mutableListOf<Deferred<Unit>>()

    private fun getUrl(cmd: String) = mapOf("https://api.kraken.com/0/private/$cmd" to "/0/private/$cmd")
    private fun getDepthUrl(currentPair: String, limit: String) = "https://api.kraken.com/0/public/Depth?pair=$currentPair&count=$limit"

    private fun browserEmulationString() = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/33.0.1750.152 Safari/537.36"
    private fun minimunOrderSizePageUrl() = "https://support.kraken.com/hc/en-us/articles/205893708-What-is-the-minimum-order-size"

    private fun getRutCurrency(cur: String) = when (cur) {
        "XBT" -> C.btc
        "XLTC" -> C.ltc
        "DASH" -> C.dsh
        else -> C.valueOf(cur.toLowerCase())
    }.toString()


    fun info(): Map<String, PairInfo>? {
        val htmlList = Jsoup.connect(minimunOrderSizePageUrl())
                .userAgent(browserEmulationString()).get().select("article li").map { it.html() }

        val minAmount = htmlList.map {
            getRutCurrency(".*?\\(([^)]*)\\).*".toRegex().matchEntire(it.split(":")[0])!!.groups.get(1)!!.value) to
            BigDecimal(it.split(":")[1].trim())
        }.toMap()

        return state.pairs.map { it.key to PairInfo(0, 0, minAmount[it.key.split("_")[0]]!!) }.toMap()
    }

    //----------------------------------------  order section  --------------------------------------------------
    override fun cancelOrders(orders: List<Order>) {
        orders.forEach {
            val params = mapOf("txid" to it.id)
            val currentOrder = it

            getUrl("CancelOrder").let {
                ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getTradesKey().first(), it, params)))
            }?.also {
                if (isNoError(it)) {
                    when (((it["result"] as Map<*, *>)["count"]) as Long) {
                        1L -> state.onActive(currentOrder.id, currentOrder.order_id, currentOrder.amount, OrderStatus.CANCELED)
                        else -> state.log.error("Order cancellation failed.")
                    }
                } else {
                    state.log.error("Error is appearance. Status error: ${it["error"].toString()}")
                }
            } ?: state.log.error("CancelOrder response failed.")
        }
    }

    override fun putOrders(orders: List<Order>) {
        orders.forEach {
            val currOrder = it
            val params = mapOf("pair" to it.pair, "type" to it.type, "ordertype" to "limit", "price" to it.rate,
                    "volume" to it.amount, "userref" to it.id)

            state.log.info("send tp play ${params.entries.joinToString { "${it.key}:${it.value}" }}")
            getUrl("AddOrder").let {
                ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getTradesKey().first(), it, params)))
            }?.also {
                if (isNoError(it)) {
                    val ret = (it["result"] as Map<*, *>)
                    val transactionId: String = (ret["txid"] as JSONArray).first() as String
                    val orderDescription = (ret["descr"] as Map<*, *>)
                    val remaining = (orderDescription["order"] as String).split(" ")[1].toBigDecimal()

                    state.log.info(" thread id: ${Thread.currentThread().id} trade ok: remaining: $remaining  transaction_id: $transactionId")

                    var status: OrderStatus = OrderStatus.COMPLETED
                    if (orderDescription["close"] == null) {
                        status = if (currOrder.amount > remaining)
                            OrderStatus.PARTIAL
                        else
                            OrderStatus.ACTIVE
                    }

                    state.onActive(currOrder.id, currOrder.order_id, currOrder.remaining - remaining, status, false)
                } else {
                    state.log.error("Error is appearance. Status error: ${it["error"].toString()}")
                }
            } ?: state.log.error("OrderPut response failed: $currOrder")
        }
    }

    override fun getOrderInfo(order: Order, updateTotal: Boolean) {
        val params = mapOf("userref" to order.id)

        getUrl("QueryOrders").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getTradesKey().first(), it, params)))?.also {
                if (isNoError(it)) {
                    val res = (it["result"] as Map<*, *>).values.first() as Map<*, *>
                    val partialAmount = BigDecimal(res["vol"].toString())
                    val status = if (res["status"].toString().equals("open")) {
                        if (order.amount > partialAmount) OrderStatus.PARTIAL else OrderStatus.ACTIVE
                    } else
                        OrderStatus.COMPLETED

                    order.takeIf { it.status != status || it.remaining.compareTo(partialAmount) != 0 }
                            ?.let { state.onActive(it.id, it.order_id, it.remaining - partialAmount, status, updateTotal) }
                } else {
                    state.log.error("Error is appearance. Status error: ${it["error"].toString()}")
                }
            } ?: state.log.error("OrderInfo response failed: $order")
        }
    }

    //--------------------------------  history and statistic section  -----------------------------------------------------
    override fun getDepth(updateTo: DepthBook?, pair: String?): DepthBook? {
        val update = updateTo ?: DepthBook()

        state.pairs.keys.forEach({
            ParseResponse(state.SendRequest(getDepthUrl(pairFromRutexToKrakenFormat(it), state.depthLimit.toString())))?.let {
                if (isNoError(it)) {
                    @Suppress("UNCHECKED_CAST")
                    (it["result"] as Map<String, *>).forEach {
                        val pairName = it.key

                        (it.value as Map<*, *>).forEach {
                            for (i in 0..(state.depthLimit - 1)) {
                                val value = ((it.value as List<*>)[i]) as List<*>
                                update.getOrPut(pairName) { mutableMapOf() }.getOrPut(BookType.valueOf(it.key.toString())) { mutableListOf() }
                                        .add(i, Depth(value[0].toString(), value[1].toString()))
                            }
                        }
                    }
                } else {
                    state.log.error("Error is appearance. Status error: ${it["error"].toString()}")
                }
            } ?: state.log.error("GetDepth response failed.")
        })

        return update
    }

    override fun getBalance(): Map<String, BigDecimal>? {
        return getUrl("Balance").let {
            (ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWalletKey(), it))))?.let {
                (it["result"] as Map<*, *>)
                        .map { getRutCurrency(it.key.toString()) to BigDecimal(it.value.toString()) }
                        .filter { state.currencies.containsKey(it.first) }.toMap()
            }
        }
    }

    override fun updateHistory(fromId: Long): Long {
        var maxTime = 0L

        state.currencies.forEach {
            var currentTimeFromFunds: Long

            //ticker may difference on kraken and rutex locally
            val asset = tickerFromRutexToKrakenFormat(it.key)
            val params = mapOf("asset" to asset)
            getUrl("DepositStatus").let {
                ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWalletKey(), it, params)))
            }?.let {
                if (isNoError(it)) {
                    val historyList = it["result"] as JSONArray

                    val total = mutableMapOf<String, BigDecimal>()
                    historyList.forEach {
                        val current = it as HashMap<*, *>
                        currentTimeFromFunds = current["time"].toString().toLong()

                        if (fromId < currentTimeFromFunds) {
                            val amount = BigDecimal(current["amount"].toString())

                            state.log.info("found incoming transfer $amount $asset, status: ${it["status"].toString().toLowerCase()}")

                            total.run { put(asset, amount + getOrDefault(asset, BigDecimal.ZERO)) }

                            if (currentTimeFromFunds > maxTime) {
                                maxTime = currentTimeFromFunds
                            }
                        }
                    }

                    total.takeIf { it.isNotEmpty() }?.forEach { state.onWalletUpdate(plus = Pair(it.key, it.value)) }

                } else {
                    state.log.error("Error is appearance. Status error: ${it["error"].toString()}")
                }
            } ?: state.log.error("UpdateHistory response failed.")
        }

        return maxTime
    }

    //--------------------------------------  service section  -------------------------------------------
    fun getApiRequest(key: StockKey, urlParam: Map<String, String>, data: Any? = null): ApiRequest {
        val nonce = "${++key.nonce}"
        val params = mutableMapOf("nonce" to nonce)
        data?.let { (it as Map<*, *>).forEach { params.put(it.key as String, "${it.value}") } }

        val payload = params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

        val path = urlParam.entries.first().value.toByteArray()

        val hmacMessage = path + MessageDigest.getInstance("SHA-256").run { digest((nonce + payload).toByteArray()) }

        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(Base64.getDecoder().decode(key.secret), "HmacSHA512")) }
        val sign = Base64.getEncoder().encodeToString(mac.doFinal(hmacMessage))

        return stock.ApiRequest(mapOf("API-Key" to key.key, "API-Sign" to sign), payload, emptyMap())
    }

    private fun ParseResponse(response: String?): JSONObject? {
        val obj = JSONParser().parse(response)

        return if (obj is JSONObject) {
            obj
        } else {
            state.log.error("Unknown error in parse response.")
            null
        }
    }

    override fun start() {
        syncWallet()
        coroutines.addAll(listOf(active(state.activeList), depth(), history(state.lastHistoryId, 2, 1),
                info(this::info, 5, state.name, state.pairs), debugWallet(state.debugWallet)))
    }

    override fun stop() {
        statePool.shutdown()
        tm.shutdown()
        state.shutdown()
    }

    override fun withdraw(address: Pair<String, String>, crossCur: String, amount: BigDecimal): Pair<Long, WithdrawStatus> {
        val data = mapOf("amount" to amount.toPlainString(), "asset" to crossCur.toUpperCase(), "key" to address.first)

        getUrl("Withdraw").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWithdrawKey(), it, data)))
        }?.let {
            if(isNoError(it)){
                val txId: String = (it["result"] as Map<*, *>)["refid"] as String
                val txHash: Long = txId.hashCode().toLong()

                return Pair(txHash, WithdrawStatus.SUCCESS)
            } else {
                state.log.error("Error is appearance. Status error: ${it["error"].toString()}")
                return Pair(0L, WithdrawStatus.FAILED)
            }

        } ?: return Pair(0L, WithdrawStatus.FAILED)

    }

    //--------------------------------------  utils section  -------------------------------------------
    private fun pairFromRutexToKrakenFormat(pair: String): String {
        pair.split("_").let {
            var resultPair = ""

            resultPair = resultPair.plus(when(it[0]){
                "btc" -> "xbt"
                else  -> it[0]
            })

            resultPair = resultPair.plus(when(it[1]){
                "btc" -> "xbt"
                else  -> it[1]
            })

            return resultPair
        }
    }

    private fun tickerFromRutexToKrakenFormat(ticker: String): String{
        return when(ticker){
            "btc" -> "xbt"
            else  -> ticker
        }
    }

    companion object {
        val Pairs = listOf(
                RutData.P(C.btc, C.usd),
                RutData.P(C.ltc, C.btc)
        )
    }

    //private fun isNoError(obj: JSONObject) = (obj["error"] == null || arrayOf(obj["error"] as JSONArray).isEmpty())
    private fun isNoError(obj: JSONObject) = (obj["error"] as JSONArray).isEmpty()
}