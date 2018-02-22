package stock

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import data.DepthBook
import data.Order
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
    override fun cancelOrders(orders: List<Order>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getDepth(updateTo: DepthBook?, pair: String?): DepthBook? {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun putOrders(orders: List<Order>) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun withdraw(address: Pair<String, String>, crossCur: String, amount: BigDecimal): Pair<Long, WithdrawStatus> {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override val state = State(this::class.simpleName!!, kodein)

    override fun updateHistory(fromId: Long): Long {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun getUrl(cmd: String) = mapOf("https://api.kraken.com/0/private/$cmd" to "/0/private/$cmd")

    override fun stop() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getBalance(): Map<String, BigDecimal>? {
        return getUrl("Balance").let {
            state.SendRequest(it.keys.first(), getApiRequest(state.getWalletKey(), it))?.let {
                (it as List<*>)
                        .filter { (it as Map<*, *>)["type"] == "exchange" }
                        .map { (it as Map<*, *>)["currency"] as String to BigDecimal((it)["available"] as String) }.toMap()
            }
        }
    }

    fun getApiRequest(key: StockKey, urlParam: Map<String, String>, data: Any? = null): ApiRequest {
        val nonce = "${++ key.nonce}"
        val params = mutableMapOf("nonce" to nonce)
        data?.let { (it as Map<*, *>).forEach { params.put(it.key as String, "${it.value}") } }

        val payload = params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

        val path = urlParam.entries.first().value.toByteArray()

        val hmacMessage = path + MessageDigest.getInstance("SHA-256").run { digest((nonce + payload).toByteArray()) }

        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(Base64.getDecoder().decode(key.secret), "HmacSHA512")) }
        val sign = Base64.getEncoder().encodeToString(mac.doFinal(hmacMessage))

        return stock.ApiRequest(mapOf("API-Key" to key.key,"API-Sign" to sign), payload, emptyMap())
    }

    override fun getOrderInfo(order: Order, updateTotal: Boolean) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    fun ParseResponse(response: String): Any? {
        val obj = JSONParser().parse(response)

        if (obj is JSONObject) {
            if (obj.containsKey("error")) {
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
}