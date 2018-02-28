package stock

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import com.sun.xml.internal.ws.developer.Serialization
import data.Depth
import data.DepthBook
import data.Order
import database.BookType
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

    //https://api.kraken.com/0/public/Depth?pair=ltcEUR&count=2

    override val state = State(this::class.simpleName!!, kodein)
    fun getUrl(cmd: String) = mapOf("https://api.kraken.com/0/private/$cmd" to "/0/private/$cmd")

    //----------------------------------------  order section  --------------------------------------------------
    override fun cancelOrders(orders: List<Order>) {
        val params = mapOf("txid" to "OKVYMR-CEEOS-N2PYWF")

        val some = getUrl("CancelOrder").let {
            (ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getTradesKey().first(), it, params))) as JSONObject)}
    }

    override fun putOrders(orders: List<Order>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getOrderInfo(order: Order, updateTotal: Boolean) {
        val params = mapOf("txid" to "OKVYMR-CEEOS-N2PYWF")

        val some = getUrl("QueryOrders").let {
            (ParseResponse(state.SendRequest(it.keys.first(), getApiRequest(state.getTradesKey().first(), it, params))) as JSONObject)}
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

    override fun updateHistory(fromId: Long): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
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
            var resultPair: String = ""

/*            if (it[0].equals("btc"))
                resultPair = resultPair.plus("xbt")
            else
                resultPair.plus(it[0])*/

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