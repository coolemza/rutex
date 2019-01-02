package stock

import RutEx
import data.Depth
import data.DepthBook
import data.Order
import database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import mu.KLoggable
import mu.KLogger
import okhttp3.*
import org.json.simple.parser.JSONParser
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance
import utils.sumByDecimal
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

abstract class Stock(final override val kodein: Kodein, final override val name: String): IStock, KodeinAware, KLoggable {
    val db: IDb by instance()

    var depthLimit = 5
    override val logger: KLogger get() = logger(name)

    val commonContext = CoroutineExceptionHandler { _, e -> logger.error(e.message, e) }
    var keys = db.getKeys(name)
    private val info = db.getStockInfo(name)
    val id = info.id
    val pairs = db.getStockPairs(name)
    val currencies = db.getStockCurrencies(name) //RutBot.rutdb.GetCurrencies(id)

//    val coroutines = mutableListOf<Deferred<Unit>>()

    var lastHistoryId = info.historyId

    lateinit var stateTime: LocalDateTime
    lateinit var walletTime: LocalDateTime
    val okHttp = OkHttpClient()
    val mediaType = MediaType.parse("application/x-www-form-urlencoded; charset=utf-8")!!

    override val depthBook = DepthBook()
    var updated = DepthBook()

    override val wallet = mapOf<String, BigDecimal>()
    val walletAvailable = mutableMapOf<String, BigDecimal>()
    val walletLocked = mutableMapOf<String, BigDecimal>()
    val walletTotal = mutableMapOf<String, BigDecimal>()
    var debugWallet = ConcurrentHashMap<String, BigDecimal>()

    var activeList = mutableListOf<Order>()

    fun getKeys(type: KeyType) = keys.filter { it.type == type }

    val walletKey: StockKey by lazy { getKeys(KeyType.WALLET).takeIf { it.isNotEmpty() }?.first()!! }
    val activeKey: StockKey by lazy { getKeys(KeyType.ACTIVE).takeIf { it.isNotEmpty() }?.first()!! }
    val withdrawKey: StockKey by lazy { getKeys(KeyType.WITHDRAW).takeIf { it.isNotEmpty() }?.first()!! }
    val historyKey: StockKey by lazy { getKeys(KeyType.HISTORY).takeIf { it.isNotEmpty() }?.first()!! }

    abstract fun balance(key: StockKey = walletKey): Map<String, BigDecimal>?
    abstract fun info(): Map<String, PairInfo>?
    abstract fun apiRequest(cmd: String, key: StockKey, data: Map<String, String>? = null, timeOut: Long = 2000): Any?
    abstract fun deposit(lastId: Long, transfers: List<Transfer>, key: StockKey = historyKey): Pair<Long, List<TransferUpdate>>
    abstract fun handleError(res: Any): Any?
    abstract fun orderInfo(order: Order, updateTotal: Boolean = true, key: StockKey = activeKey): OrderUpdate?
    abstract fun withdraw(transfer: Transfer, key: StockKey = withdrawKey): Pair<TransferStatus, String>

    override fun withdraw(transfer: Transfer): Pair<TransferStatus, String> = withdraw(transfer, withdrawKey)

    fun debugWallet() = GlobalScope.launch {
        while (isActive) {
            try {
                withContext(NonCancellable) {
                    balance()?.let { debugWallet.putAll(it) }
                }
            } catch (e: Exception) {
                logger.error(e.message, e)
            }
            delay(TimeUnit.SECONDS.toMillis(10))
        }
    }

    fun getLocked(orderList: MutableList<Order> = activeList) = orderList.groupBy { it.getLockCur() }
            .map { it.key to it.value.sumByDecimal { it.getLockAmount() } }.toMap()

    fun history(delay: Long = 10) = GlobalScope.launch {
        while (isActive) {
            db.getTransfer(name).let {
                withContext(NonCancellable) { updateTransfer(it) }
                delay(TimeUnit.SECONDS.toMillis(delay))
            }
        }
    }

    fun updateDeposit(cur: String) = GlobalScope.launch {
        db.getTransfer(name).filter { it.cur == cur }
                .let { withContext(NonCancellable) { updateTransfer(it) } }
    }

    fun infoPolling(delay: Long = 600) = GlobalScope.launch {
        val lastPair = db.getStockPairs(name)
        while (isActive) {
            withContext(NonCancellable) {
                info()?.let {
                    val newList = it.filter { it.value.minAmount.compareTo(lastPair[it.key]!!.minAmount) != 0 }
                    if (newList.isNotEmpty()) {
                        db.updateStockPairs(newList, name)
                        newList.forEach { lastPair[it.key]!!.minAmount = it.value.minAmount }
                        RutEx.stateLock.withLock { newList.forEach { pairs[it.key]!!.minAmount = it.value.minAmount } }
                    }
                }
            }
            delay(TimeUnit.SECONDS.toMillis(delay))
        }
    }

    suspend fun onActiveUpdate(update: OrderUpdate) = onActive(update)?.let { onWalletUpdate(plus = it.plus, minus = it.minus) }

    suspend fun onActive(update: OrderUpdate): UpdateWallet? {
        var updateWallet: UpdateWallet? = null
        RutEx.stateLock.withLock {
            val order = activeList.find { if (update.id != null) it.id == update.id else it.stockOrderId == update.orderId }

            if (order == null) {
                logger.error("Thread id: ${Thread.currentThread().id} id: ${update.id} order: ${update.orderId} not found in activeList, possibly it not from bot or already removed")
                return null
            }

            takeIf { order.stockOrderId == "0" }?.run {
                logger.info("order: ${order.stockOrderId} -> ${update.orderId}, deal_id = ${order.id}")
                order.stockOrderId = update.orderId!!
            }

            update.status?.let {
                logger.info("order: ${update.orderId} status: ${order.status} -> $it")
                order.status = it
            }

            when (order.status) {
                OrderStatus.ACTIVE, OrderStatus.PARTIAL, OrderStatus.COMPLETED -> {
                    if (update.amount != null) {
                        val newRemainig = order.remaining - update.amount
                        logger.info("order: ${update.orderId} remaining: ${order.remaining} -> $newRemainig amount: ${update.amount}, status: ${order.status}")
                        order.remaining = newRemainig
                        val complete = order.remaining.compareTo(BigDecimal.ZERO) == 0

                        if (complete) {
                            logger.info("id: ${order.id} order: ${order.stockOrderId} removed from orderList, status: ${order.status}")
                            activeList.remove(order)
                            activeList.let { logger.info("active list:\n" + it.joinToString("\n") { it.toString() }) }
                        }

                        updateWallet = UpdateWallet(Pair(order.getToCur(), order.getPlayedAmount(update.amount)),
                                Pair(order.getFromCur(), order.getLockAmount(update.amount)))
                        GlobalScope.launch(commonContext) { RutEx.botUpdate(order.botId, update) }
                    } else {

                    }
                }
                OrderStatus.CANCELED, OrderStatus.FAILED -> {
                    logger.info("id: ${order.id}, order: ${order.stockOrderId} removing from orderList, status: ${order.status}")
                    activeList.remove(order)
                    updateWallet = UpdateWallet()
                    GlobalScope.launch(commonContext) { RutEx.botUpdate(order.botId, update) }
                }
            }
        }
        return updateWallet
    }

    suspend fun OnStateUpdate(update: List<Update>? = null, fullState: DepthBook? = null): Boolean {
        val time = LocalDateTime.now()
        stateTime = time

        RutEx.stateLock.withLock {
            fullState?.also { GlobalScope.launch(commonContext) { db.saveBook(id, time, fullState = it) } }?.let {
                depthBook.replace(it)
                updated.replace(it)
                logger.debug("$name full state updated for (${it.pairs.size}) pairs")
            }

            update?.also { GlobalScope.launch(commonContext) { db.saveBook(id, time, it) } }?.onEach { upd ->
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

    suspend fun onWalletUpdate(update: Map<String, BigDecimal>? = null, plus: Pair<String, BigDecimal>? = null, minus: Pair<String, BigDecimal>? = null) {
        RutEx.stateLock.withLock {
            val total = mutableMapOf<String, BigDecimal>().apply { putAll(walletTotal) }

            update?.let { total.putAll(it) }
            plus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) + amount) } }
            minus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) - amount) } }

            val locked = getLocked()
            val available = total.entries.associateBy({ it.key }) { it.value - locked.getOrDefault(it.key, BigDecimal.ZERO) }

            val time = LocalDateTime.now().also { walletTime = it }

            mapOf(WalletType.AVAILABLE to available, WalletType.LOCKED to locked, WalletType.TOTAL to total).let {
                GlobalScope.launch { db.saveWallets(it, id, time) }
            }

            available.also { walletAvailable.putAll(it) }
            total.let { walletTotal.putAll(it) }
            locked.let { walletLocked.run { clear(); putAll(it) } }

            mapOf("total" to walletTotal, "available" to walletAvailable, "locked" to walletLocked, "update" to wallet)
                    .forEach { logger.info("$name ${it.key}: ${it.value}") }
            logger.info("$name avaBOT: ${walletAvailable.toSortedMap()}")
        }
    }

    fun parseJsonResponse(response: String?) = try {
        response?.let {
            logger.trace(response)
            handleError(JSONParser().parse(it))
        }
    } catch (e: Exception) {
        run { logger.error(e.message, e) }.run { null }
    }

    fun syncWallet() = runBlocking {
        //TODO: synchronize wallet and History() on start
        var walletSynchronized = false

        do {
            val beginWallet = balance()
            activeList.forEach { orderInfo(it, false) }
            db.getTransfer(name).let { updateTransfer(it) }
            val endWallet = balance()
            if (beginWallet != null && endWallet != null) {
                if (beginWallet.all { it.value == endWallet[it.key]!! }) {
                    val locked = getLocked()
                    val total = endWallet.map { it.key to it.value + locked.getOrDefault(it.key, BigDecimal.ZERO) }.toMap()
                    onWalletUpdate(update = total)
                    walletSynchronized = true
                }
            }
        } while (!walletSynchronized)
    }

    suspend fun updateTransfer(transfers: List<Transfer>) {
        deposit(lastHistoryId, transfers).let {
            if (it.first > lastHistoryId) {
                db.saveHistoryId(it.first, id)
                lastHistoryId = it.first
            }
            it.second.forEach { tu ->
                val ts = transfers.find { it.id == tu.id }!!
                if (tu.status != ts.status) {
                    if (ts.status == TransferStatus.SUCCESS) {
                        onWalletUpdate(plus = Pair(ts.cur, ts.amount))
                    }
                    db.saveTransfer(ts)
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