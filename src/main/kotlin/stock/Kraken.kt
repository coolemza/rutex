package stock

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import com.sun.xml.internal.ws.developer.Serialization
import data.Depth
import data.DepthBook
import data.Order
import database.BookType
import database.OrderStatus
import database.StockCurrencyInfo
import database.StockKey
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.math.BigDecimal
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Kraken(override val kodein: Kodein) : IStock, KodeinAware {
    override val state = State(this::class.simpleName!!, kodein)
    fun getUrl(cmd: String) = mapOf("https://api.kraken.com/0/private/$cmd" to "/0/private/$cmd")

    //----------------------------------------  order section  --------------------------------------------------
    override fun cancelOrders(orders: List<Order>) {
        orders.forEach {
            val params = mapOf("txid" to it.transactionId)
            var currentOrder = it

            getUrl("CancelOrder").let {
                (ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getTradesKey().first(), it, params))) as JSONObject)
            }?.also {
                when(((it["result"] as Map<*, *>)["count"]) as Long){
                    1L -> {
                        currentOrder.status = OrderStatus.CANCELED

                        //state.onActive()  --  обновление на стороне сервера
                    }
                    else -> state.log.error("Order cancellation failed.")
                }
            }
        }
    }

    //1. Нет order_id только transaction id
    //2.
    override fun putOrders(orders: List<Order>) {
        orders.forEach{
            var currOrder = it
            val params = mapOf("pair" to it.pair, "type" to it.type, "ordertype" to "limit", "price" to it.rate,
                    "volume" to it.amount)

            state.log.info("send tp play ${params.entries.joinToString { "${it.key}:${it.value}" }}")
            val some = getUrl("AddOrder").let {
                (ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getTradesKey().first(), it, params))) as JSONObject)
            }?.also {
                //it
                val ret = (it["result"] as Map<*, *>)
                val transactionId: String = (ret["txid"] as JSONArray).first() as String
                val orderDescription = (ret["descr"] as Map<*, *>)
                val remaining = (orderDescription["order"] as String).split(" ")[1].toBigDecimal()

                state.log.info(" thread id: ${Thread.currentThread().id} trade ok: remaining: ${remaining}  transaction_id: ${transactionId}")

                //TO-DO: Скорректировать единицу
                val status = when (1L) {
                    0L -> OrderStatus.COMPLETED
                    else -> if (currOrder.amount > remaining) OrderStatus.PARTIAL else OrderStatus.ACTIVE
                }

                //TO-DO: Заменить единицу на order_id
                state.onActive(currOrder.id, 1, currOrder.remaining - remaining, status)
            }
        }
    }

    override fun getOrderInfo(order: Order, updateTotal: Boolean) {
        val params = mapOf("txid" to order.transactionId)

        getUrl("QueryOrders").let {
            (ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getTradesKey().first(), it, params))) as JSONObject)?.also {
                //TODO: int or Long? or String?
                val res = (it["result"] as Map<*, *>).values.first() as Map<*, *>
                val partialAmount = BigDecimal(res["vol"].toString())
                val status = if (res["status"].toString().equals("open")) {
                    if (order.amount > partialAmount) OrderStatus.PARTIAL else OrderStatus.ACTIVE
                } else
                    OrderStatus.COMPLETED

                order.takeIf { it.status != status || it.remaining.compareTo(partialAmount) != 0 }
                        ?.let { state.onActive(it.id, it.order_id, it.remaining - partialAmount, status, updateTotal) }
            }
        }
    }

    //--------------------------------  history and statistic section  -----------------------------------------------------
    override fun getDepth(updateTo: DepthBook?, pair: String?): DepthBook? {
        val update = if (updateTo != null) updateTo else DepthBook()

        state.pairs.keys.forEach {
            ParseResponse(state.SendRequest(getDepthUrl(pairFromRutexToKrakenFormat(it), state.depthLimit.toString()))).let {
                ((it as Map<String, *>)["result"] as Map<String, *>).forEach {
                    val pairName = it.key

                    (it.value as Map<String, *>).forEach {
                        for (i in 0..(state.depthLimit - 1)) {
                            val value = ((it.value as List<*>)[i]) as List<*>
                            update.getOrPut(pairName) { mutableMapOf() }.getOrPut(BookType.valueOf(it.key.toString())) { mutableListOf() }
                                    .add(i, Depth(value[0].toString(), value[1].toString()))
                        }
                    }
                }
            }
        }

        return update
    }

    override fun getBalance(): Map<String, BigDecimal>? {
        return getUrl("Balance").let {
            (ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWalletKey(), it))) as JSONObject)?.let {
                (it["result"] as Map<*, *>)
                        .map { it.key.toString() to BigDecimal(it.value.toString()) }.toMap()
            }
        }
    }

    /*override fun updateHistory(fromId: Long): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }*/


    override fun updateHistory(fromId: Long): Long {
        var lastId = fromId


        //var openOrders = getUrl("OpenOrders").let {
            //(ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getHistoryKey(), it))) as JSONObject)}
            //(ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWalletKey(), it, mapOf("userref" to "O4SIEB-QHTBQ-G4ZZVV")))) as JSONObject)}

        //openOrders



        var openOrders = getUrl("OpenOrders").let {
            (ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getHistoryKey(), it))) as JSONObject)}?.also{

            val res = (it["result"] as Map<String, *>).toSortedMap()

            val total = mutableMapOf<String, BigDecimal>()
            val cc = res as Map<String, Map<String, *>>

            res.forEach{
                val cur = (it.value["currency"] as String).toLowerCase()
                val amount = BigDecimal(it.value["amount"].toString())

                state.log.info("found incoming transfer ${amount} $cur, status: ${it.value["status"]}")

                total.run { put(cur, amount + getOrDefault(cur, BigDecimal.ZERO)) }
            }

            total.takeIf { it.isNotEmpty() }?.forEach { state.onWalletUpdate(plus = Pair(it.key, it.value)) }
            lastId = res.lastKey().toLong()

        }



        /*getUrl("TransHistory").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getHistoryKey(), it, mapOf("order" to "DESC", "from_id" to fromId))))?.also {
                //TODO: int or Long? or String?
                val res = (it["return"] as Map<String, *>).toSortedMap()
                if (res.containsKey(fromId.toString())) {
                    res.remove(fromId.toString())
                    if (res.isNotEmpty()) {
                        state.log.info("new history id: $fromId")
                        val cc = res as Map<String, Map<String, *>>
                        val total = mutableMapOf<String, BigDecimal>()
                        cc.filterValues { it["type"] == 1L }.forEach {
                            val cur = (it.value["currency"] as String).toLowerCase()
                            val amount = BigDecimal(it.value["amount"].toString())

                            state.log.info("found incoming transfer ${amount} $cur, status: ${it.value["status"]}")

                            total.run { put(cur, amount + getOrDefault(cur, BigDecimal.ZERO)) }
                        }
                        total.takeIf { it.isNotEmpty() }?.forEach { state.onWalletUpdate(plus = Pair(it.key, it.value)) }
                        lastId = res.lastKey().toLong()
                    }
                } else {
                    state.log.error("history id not found!!!")
                }
            } ?: state.log.error("updateHistory failed")
        }*/


        return lastId
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

    fun ParseResponse(response: String?): Any? {
        val obj = JSONParser().parse(response)

        if (obj is JSONObject) {
            if (obj.containsKey("error") && (obj["error"] != null && arrayOf(obj["error"]).isEmpty())) {
                state.log.error(obj["error"].toString())
                return null
            }
            return obj
        } else if (obj is JSONArray) {
            return obj
        } else {
            state.log.error("unknown")
            return null
        }
    }

    override fun start() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun stop() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun withdraw(address: Pair<String, String>, crossCur: String, amount: BigDecimal): Pair<Long, WithdrawStatus> {
        //val data = mapOf("amount" to amount.toPlainString(), "asset" to crossCur.toUpperCase(), "address" to address.first)
        val data = mapOf("amount" to BigDecimal.valueOf(0.01), "asset" to "LTC", "key" to "Kraken")


       // https://api.kraken.com/0/private/WithdrawInfo

        //https://api.kraken.com/0/private/Withdraw

         var some = getUrl("Withdraw").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWithdrawKey(), it, data))) as JSONObject}

          //some

        return Pair(0L, WithdrawStatus.FAILED);

/*        return getUrl("WithdrawCoin").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWithdrawKey(), it, data)))?.let {
                val res = it["return"] as Map<*, *>
                if (it["success"] == 1L) {
                    return Pair(res["tId"] as Long, WithdrawStatus.SUCCESS)
                } else {
                    return Pair(0L, WithdrawStatus.FAILED)
                }
            } ?: return Pair(0L, WithdrawStatus.FAILED)
        }*/
    }

    /*override fun withdraw(address: Pair<String, String>, crossCur: String, amount: BigDecimal): Pair<Long, WithdrawStatus> {
        val data = mapOf("amount" to amount.toPlainString(), "coinName" to crossCur.toUpperCase(), "address" to address.first)

        return getUrl("WithdrawCoin").let {
            ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getWithdrawKey(), it, data)))?.let {
                val res = it["return"] as Map<*, *>
                if (it["success"] == 1L) {
                    return Pair(res["tId"] as Long, WithdrawStatus.SUCCESS)
                } else {
                    return Pair(0L, WithdrawStatus.FAILED)
                }
            } ?: return Pair(0L, WithdrawStatus.FAILED)
        }
    }*/

    //--------------------------------------  utils section  -------------------------------------------
    fun pairFromRutexToKrakenFormat(pair: String): String {
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

    fun pairFromKrakenToRutexFormat(pair: String)= ""
    fun getDepthUrl(currentPair: String, limit: String) = "https://api.kraken.com/0/public/Depth?pair=${currentPair}&count=${limit}"
}