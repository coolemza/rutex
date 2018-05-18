package stock

import RutEx
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import com.github.salomonbrys.kodein.instance
import data.Depth
import data.DepthBook
import data.Order
import database.*
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.CoroutineExceptionHandler
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.coroutines.experimental.sync.withLock
import mu.KLoggable
import okhttp3.*
import utils.sumByDecimal
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class State(val name: String, override val kodein: Kodein): KodeinAware, KLoggable {
    val db: IDb = instance()

    var depthLimit = 5
//    val log = getRollingLogger(name, Level.DEBUG)
    override val logger = logger(name)

    private val commonContext = CommonPool + CoroutineExceptionHandler { _, e -> logger.error(e.message, e) }
    private val keys = db.getKeys(name)
    private val info = db.getStockInfo(name)
    val id = info.id
    val pairs = db.getStockPairs(name)
    val currencies = db.getStockCurrencies(name) //RutBot.rutdb.GetCurrencies(id)

//    val coroutines = mutableListOf<Deferred<Unit>>()

    var lastHistoryId = info.historyId

    lateinit var stateTime: LocalDateTime
    lateinit var walletTime: LocalDateTime
    private val okHttp = OkHttpClient()
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
//    fun getTradesKey() = keys.filter { it.type == KeyType.TRADE }

    private val tradeLock = Mutex()
    private val tList = keys.filter { it.type == KeyType.TRADE }

    suspend fun getTradeKeys(orders: List<Order>) = tradeLock.withLock {
        tList.filter { !it.busy }.takeIf { it.size >= orders.size }?.let {
            orders.mapIndexed { i, order -> order to it[i] }.onEach { it.second.busy = true }
        } ?: orders.forEach { onActive(it.id, it.order_id, BigDecimal.ZERO, OrderStatus.FAILED) }
                .also { logger.error("not enough threads for Trading!!!") }.let { null }
    }

    suspend fun releaseTradeKey(key: StockKey) = tradeLock.withLock { key.busy = false }

    //fun getListActiveOrders(): List<Order> {return activeList}

    fun getLocked(orderList: MutableList<Order> = activeList) =  orderList.groupBy { it.getLockCur() }
            .map { it.key to it.value.sumByDecimal { it.getLockAmount() } }.toMap()

    suspend fun OnStateUpdate(update: List<Update>? = null, fullState: DepthBook? = null): Boolean {
        val time = LocalDateTime.now()
        stateTime = time

        RutEx.stateLock.withLock {
            fullState?.also { launch(commonContext) { db.saveBook(id, time, fullState = it) } }?.let {
                depthBook.replace(it)
                updated.replace(it)
                logger.debug("$name full state updated for (${it.pairs.size}) pairs")
            }

            update?.also { launch(commonContext) { db.saveBook(id, time, it) } }?.onEach { upd ->
                if (depthBook.pairs[upd.pair]!![upd.type]!!.size < 2) {
                    logger.error("size < 2, pair: ${upd.pair}")
                } else {
                    if (!(if (upd.amount == null) depthBook.removeRate(upd) else depthBook.updateRate(upd))) {
                        logger.error("rate not found $upd")
                        return@withLock null
                    }

                    updated.pairs.getOrPut(upd.pair) { mutableMapOf() }.getOrPut(upd.type) { mutableListOf() }
                            .add(0, Depth(upd.rate, upd.amount ?: BigDecimal.ZERO))
                }
            }?.also { logger.debug("$name state updated (${it.size})") }
        }
        updated.pairs.clear()
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

        mapOf("total" to walletTotal, "available" to walletAvailable, "locked" to walletLocked, "update" to wallet)
                .forEach { logger.info("$name ${it.key}: ${it.value}") }
        logger.info("${name} avaBOT: ${walletAvailable.toSortedMap()}")
    }

    fun onActive(deal_id: Long?, order_id: Long, amount: BigDecimal? = null, status: OrderStatus? = null, updateTotal: Boolean = true) {
        val order = activeList.find { if (deal_id != null) it.id == deal_id else it.order_id == order_id }

        if (order == null) {
            logger.error("Thread id: ${Thread.currentThread().id} id: $deal_id order: $order_id not found in activeList, possibly it not from bot or already removed")
            return
        }

        takeIf { order.order_id == 0L }?.run {
            logger.info("order: ${order.order_id} -> $order_id, deal_id = ${order.id}")
            order.order_id = order_id
        }

        status?.let {
            logger.info("order: $order_id status: ${order.status} -> $it")
            order.status = it
        }

        when (order.status) {
            OrderStatus.ACTIVE, OrderStatus.PARTIAL, OrderStatus.COMPLETED -> {
                if (amount != null) {
                    val newRemainig = order.remaining - amount
                    logger.info("order: $order_id remaining: ${order.remaining} -> $newRemainig amount: $amount, status: ${order.status}")
                    order.remaining = newRemainig
                    val complete = order.remaining.compareTo(BigDecimal.ZERO) == 0

                    if (complete) {
                        logger.info("id: ${order.id} order: ${order.order_id} removed from orderList, status: ${order.status}")
                        activeList.remove(order)
                        activeList.let { logger.info("active list:\n" + it.joinToString("\n") { it.toString() }) }
                    }
                    if (updateTotal) {
                        onWalletUpdate(plus = Pair(order.getToCur(), order.getPlayedAmount(amount)),
                                minus = Pair(order.getFromCur(), order.getLockAmount(amount)))
                    }

                } else {

                }
            }
            OrderStatus.CANCELED, OrderStatus.FAILED -> {
                logger.info("id: ${order.id}, order: ${order.order_id} removing from orderList, status: ${order.status}")
                if (updateTotal) {
                    activeList.remove(order)
                    onWalletUpdate()
                }

            }
        }
    }

    fun SendRequest(url: String, headers: Map<String, String>? = null, postData: String = "", timeOut: Long = 2000): String? {
        logger.trace("begin ${LocalDateTime.now()}")
        okHttp.newBuilder().connectTimeout(timeOut, TimeUnit.MILLISECONDS)
        try {
            val request = Request.Builder().url(url).apply {
                headers?.run { headers(Headers.of(headers)).post(RequestBody.create(mediaType, postData.toByteArray())) }
            }

            val response = okHttp.newCall(request.build()).execute()
            response.body()?.string()?.let { return it }

            logger.error("null body received")
            return null
        } catch (e: SocketTimeoutException) {
            logger.warn(e.message)
            logger.warn(" $url")
            logger.trace("end ${LocalDateTime.now()}")
            return null
        } catch (e: IOException) {
            logger.error(e.message)
            return null
        }
    }

    fun shutdown() {
//        log.info("waiting dispatcher..")
////        okHttp.dispatcher().executorService().shutdown()
//
//        log.info("stopping coroutines")
//        try {
//            runBlocking { coroutines.forEach {
//                try {
//                    log.info("stopping $it")
//                    it.cancelAndJoin()
//                    log.info("stopped $it")
//                } catch (e: Exception) {
//                    log.info("runblock exception!!")
//                    log.error(e.message,e)
//                }
//            } }
//        } catch (e:Exception) {
//            log.error(e.message,e)
//        }

        logger.info("saving all nonce's")
        keys.forEach { db.saveNonce(it) }
    }
}