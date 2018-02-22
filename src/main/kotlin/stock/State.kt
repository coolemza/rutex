package stock

import ch.qos.logback.classic.Level
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import com.github.salomonbrys.kodein.instance
import data.Depth
import data.DepthBook
import data.Order
import database.IDb
import database.KeyType
import database.OrderStatus
import database.WalletType
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.cancelAndJoin
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.*
import utils.getRollingLogger
import utils.sumByDecimal
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class State(val name: String, override val kodein: Kodein): KodeinAware {
    val db: IDb = instance()

    var depthLimit = 5
    val log = getRollingLogger(name, Level.DEBUG)

    val info = db.getStockInfo(name)
    val id = info.id
    val keys = db.getKeys(name)
    val pairs = db.getStockPairs(name)
    val currencies = db.getStockCurrencies(name) //RutBot.rutdb.GetCurrencies(id)

    val coroutines = mutableListOf<Deferred<Unit>>()

    var lastHistoryId = info.historyId

    lateinit var stateTime: LocalDateTime
    lateinit var walletTime: LocalDateTime
    val okHttp = OkHttpClient()
    private val mediaType = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8")!!

    val depthBook = DepthBook()
    var updated = DepthBook()

    val wallet = mapOf<String, BigDecimal>()
    val walletAvailable = mutableMapOf<String, BigDecimal>()
    val walletLocked = mutableMapOf<String, BigDecimal>()
    val walletTotal = mutableMapOf<String, BigDecimal>()
    var debugWallet = ConcurrentHashMap<String, BigDecimal>()

    var activeList = mutableListOf<Order>()

    fun getWalletKey() = keys.find { it.type == KeyType.WALLET }!!
    fun getActiveKey() = keys.find { it.type == KeyType.ACTIVE }!!
    fun getWithdrawKey() = keys.find { it.type == KeyType.WITHDRAW }!!
    fun getHistoryKey() = keys.find { it.type == KeyType.HISTORY }!!
    fun getTradesKey() = keys.filter { it.type == KeyType.TRADE }

    fun getLocked(orderList: MutableList<Order> = activeList) =  orderList.groupBy { it.getLockCur() }
            .map { it.key to it.value.sumByDecimal { it.getLockAmount() } }.toMap()

    fun OnStateUpdate(update: List<Update>): Boolean {
        val time = LocalDateTime.now()
        stateTime = time

        update.forEach { upd ->
            val err = upd.amount?.let { depthBook.updateRate(upd.pair, upd.type, upd.rate, it) } ?: depthBook.removeRate(upd.pair, upd.type, upd.rate)
            err?.let {
                log.error(err)
                return false
            }
            updated.getOrPut(upd.pair) { mutableMapOf() }.getOrPut(upd.type) { mutableListOf() }
                    .add(0, Depth(upd.rate, upd.amount ?: BigDecimal.ZERO))
        }

        launch { db.saveBook(id, update, time, pairs) }

        updated.clear()
        return true
    }

    fun onWalletUpdate(update: Map<String, BigDecimal>? = null, plus: Pair<String, BigDecimal>? = null, minus: Pair<String, BigDecimal>? = null) {
            val total = mutableMapOf<String, BigDecimal>().apply { putAll(walletTotal) }

            update?.let { total.putAll(it) }
            plus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) + amount) } }
            minus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) - amount) } }

            val locked = getLocked()
            val available = total.entries.associateBy({ it.key }) { it.value - locked.getOrDefault(it.key, BigDecimal.ZERO) }

            val time = LocalDateTime.now().also { walletTime = it }

            mapOf(WalletType.AVAILABLE to available, WalletType.LOCKED to locked, WalletType.TOTAL to total).let {
                launch { db.saveWallets(it, id, time) }
            }

            available.also { walletAvailable.putAll(it) }
            total.let { walletTotal.putAll(it) }
            locked.let { walletLocked.run { clear(); putAll(it) } }

            mapOf("total" to walletTotal, "available" to walletAvailable, "locked" to walletLocked, "update" to wallet).forEach { log.info("${it.key}: ${it.value}") }
            log.info("avaBOT: ${walletAvailable.toSortedMap()}")
    }

    fun onActive(deal_id: Long?, order_id: Long, amount: BigDecimal? = null, status: OrderStatus? = null, updateTotal: Boolean = true) {
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
        }
    }

    fun SendRequest(url: String, ap: ApiRequest? = null): String? {
        okHttp.newBuilder().connectTimeout(2000, TimeUnit.MILLISECONDS)
        try {
            val request = Request.Builder().url(url).apply {
                ap?.run { headers(Headers.of(ap.headers)).post(RequestBody.create(mediaType, ap.postData.toByteArray())) }
            }

            val response = okHttp.newCall(request.build()).execute()
            response.body()?.string()?.let { return it }

            log.error("null body received")
            return null
        } catch (e: SocketTimeoutException) {
            log.warn(e.message)
            log.warn(" $url")
            return null
        } catch (e: IOException) {
            log.error(e.message)
            return null
        }
    }

    fun shutdown() {
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
        keys.forEach { db.saveNonce(it) }
    }
}