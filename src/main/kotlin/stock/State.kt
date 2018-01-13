package stock

import ch.qos.logback.classic.Level
import data.Depth
import data.DepthBook
import data.Order
import db.*
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.cancelAndJoin
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.*
import org.joda.time.DateTime
import utils.getRollingLogger
import utils.sumByDecimal
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class State(override val name: String): IState {
    override var depthLimit = 5
    override val log = getRollingLogger(name, Level.DEBUG)

    override var id =  getStockId(name)
    override var keys = getKeys(name)
    override var pairs = getPairs(name)
    override var currencies = getCurrencies(name)
    override var activeList = mutableListOf<Order>()
    override var debugWallet = ConcurrentHashMap<String, BigDecimal>()

    override val coroutines = mutableListOf<Deferred<Unit>>()

    override var lastHistoryId = getLastHistoryId()

    override lateinit var stateTime: LocalDateTime
    lateinit var walletTime: LocalDateTime
    private var lastStateTime = LocalDateTime.now()
    val okHttp = OkHttpClient()
    val mediaType = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8")!!

    override val socketState = DepthBook()
    override var updated = DepthBook()
    override val wallet = mapOf<String, BigDecimal>()
    val walletAvailable = mutableMapOf<String, BigDecimal>()
    val walletLocked = mutableMapOf<String, BigDecimal>()
    val walletTotal = mutableMapOf<String, BigDecimal>()

    override fun getLocked(orderList: MutableList<Order>) =  orderList.groupBy { it.getLockCur() }
            .map { it.key to it.value.sumByDecimal { it.getLockAmount() } }.toMap()

    override fun OnStateUpdate(update: List<Update>): Boolean {
        val time = LocalDateTime.now()
        val dateTime = DateTime.now()
        stateTime = time

        update.forEach { upd ->
            val err = upd.amount?.let { socketState.updateRate(upd.pair, upd.type, upd.rate, it) } ?: socketState.removeRate(upd.pair, upd.type, upd.rate)
            err?.let {
                log.error(err)
                return false
            }
            updated.getOrPut(upd.pair) { mutableMapOf() }.getOrPut(upd.type) { mutableListOf() }
                    .add(0, Depth(upd.rate, upd.amount ?: BigDecimal.ZERO))
        }

        launch { saveBook(id, update, dateTime, pairs) }

        updated.clear()
        return true
    }

    override fun onWalletUpdate(update: Map<String, BigDecimal>?, plus: Pair<String, BigDecimal>?, minus: Pair<String, BigDecimal>?) {
            val total = mutableMapOf<String, BigDecimal>().apply { putAll(walletTotal) }

            update?.let { total.putAll(it) }
            plus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) + amount) } }
            minus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) - amount) } }

            val locked = getLocked()
            val available = total.entries.associateBy({ it.key }) { it.value - locked.getOrDefault(it.key, BigDecimal.ZERO) }

            val time = LocalDateTime.now().also { walletTime = it }
            val dateTime = DateTime.now()

            mapOf(WalletType.AVAILABLE to available, WalletType.LOCKED to locked, WalletType.TOTAL to total).let {
                launch { saveWallets(it, id, dateTime) }
            }

            available.also { walletAvailable.putAll(it) }
            total.let { walletTotal.putAll(it) }
            locked.let { walletLocked.run { clear(); putAll(it) } }

            mapOf("total" to walletTotal, "available" to walletAvailable, "locked" to walletLocked, "update" to wallet).forEach { log.info("${it.key}: ${it.value}") }
            log.info("avaBOT: ${walletAvailable.toSortedMap()}")
    }

    override fun onActive(deal_id: Long?, order_id: Long, amount: BigDecimal?, status: OrderStatus?, updateTotal: Boolean) {
        val order = activeList.find { if (deal_id != null) it.id == deal_id else it.order_id == order_id }

        if (order == null) {
            log.error("Thread id: ${Thread.currentThread().id} id: $deal_id order: $order_id not found in activeList, possibly it not from bot or already removed")
            return
        }

        takeIf { order.order_id == 0L }?.run {
            log.info("order: ${order.order_id} -> $order_id, deal_id = ${order.id}")
            order.order_id = order_id
        }

        status?.let {
            log.info("order: $order_id status: ${order.status} -> $it")
            order.status = it
        }

        when (order.status) {
            OrderStatus.ACTIVE, OrderStatus.PARTIAL, OrderStatus.COMPLETED -> {
                if (amount != null) {
                    val newRemainig = order.remaining - amount
                    log.info("order: $order_id remaining: ${order.remaining} -> $newRemainig amount: $amount, status: ${order.status}")
                    order.remaining = newRemainig
                    val complete = order.remaining.compareTo(BigDecimal.ZERO) == 0

                    if (complete) {
                        log.info("id: ${order.id} order: ${order.order_id} removed from orderList, status: ${order.status}")
                        activeList.remove(order)
                        activeList.let { log.info("active list:\n" + it.joinToString("\n") { it.toString() }) }
                    }
                    if (updateTotal) {
                        onWalletUpdate(plus = Pair(order.getToCur(), order.getPlayedAmount(amount)),
                                minus = Pair(order.getFromCur(), order.getLockAmount(amount)))
                    }

                } else {

                }
            }
            OrderStatus.CANCELED, OrderStatus.FAILED -> {
                log.info("id: ${order.id}, order: ${order.order_id} removing from orderList, status: ${order.status}")
                if (updateTotal) {
                    activeList.remove(order)
                    onWalletUpdate()
                }

            }
            else -> {
            }
        }
    }

    override fun SendRequest(urlParam: Map<String, String>, ap: ApiRequest?): String? {
        val begin = LocalDateTime.now()
        okHttp.newBuilder().connectTimeout(2000, TimeUnit.MILLISECONDS)
        try {
            val request = Request.Builder().url(urlParam.entries.first().key).apply {
                ap?.run { headers(Headers.of(ap.headers)).post(RequestBody.create(mediaType, ap.postData.toByteArray())) }
            }

            val response = okHttp.newCall(request.build()).execute()
            response.body()?.string()?.let {
                try {
                    return it
                } catch (e: Exception) {
                    log.error("response parsing error: $it")
                    return null
                }
            }

            log.error("null body received")
            return null
        } catch (e: SocketTimeoutException) {
            log.warn(e.message)
            log.warn(" ${urlParam.entries.first().key}")
            return null
        } catch (e: IOException) {
            log.error(e.message)
            return null
        }
    }

    override fun shutdown() {
        log.info("waiting dispatcher..")
        okHttp.dispatcher().executorService().shutdown()

        log.info("stopping coroutines")
        try {
            runBlocking { coroutines.forEach {
                try {
                    log.info("stopping $it")
                    it.cancelAndJoin()
                    log.info("stopped $it")
                } catch (e: Exception) {
                    log.info("runblock exception!!")
                    log.error(e.message,e)
                }
            } }
        } catch (e:Exception) {
            log.error(e.message,e)
        }

        log.info("saving all nonce's")
        keys.forEach { saveNonce(it) }
    }
}