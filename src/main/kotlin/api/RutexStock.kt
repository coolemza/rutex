package api

import data.*
import database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import mu.KLoggable
import org.json.simple.parser.JSONParser
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance
import utils.IHttp
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

abstract class RutexStock(final override val kodein: Kodein, final override val name: String) : IStock, KodeinAware,
    KLoggable {
    val db: IDb by instance()
    val runningStocks: Set<String> by kodein.instance("stocks")

    override val logger = this.logger(name)

    val handler = CoroutineExceptionHandler { _, e -> logger.error(e.message, e) }
    val channel = Channel<Pair<List<Update>?, DepthBook?>>()

    val userAgent = "Chrome/33.0.1750.152 Safari/537.36"
    val info = db.getStockInfo(name)
    val id = info.id
    val keys = db.getKeys(name)
    override val pairs = db.getStockPairs(name)
    override val currencies = db.getStockCurrencies(name)
    override val activeList = mutableListOf<Order>()

    protected val coroutines = mutableListOf<Job>()
    lateinit var cor: List<Job>
    private val EMPTY_LAMBDA: suspend () -> Unit = {}

    var lastHistoryId = info.historyId

    override var depthLimit = 5
    override val tradeAttemptCount = 30

    val http: IHttp by instance()

    protected val internalBook = DepthBook()
    override val depthBook = DepthBook()
    override val depthChannel = Channel<ControlMsg>(capacity = Channel.CONFLATED)

    override val wallet = mutableMapOf<String, BigDecimal>()

    fun getKeys(type: KeyType) = keys.filter { it.type == type }.takeIf { it.isNotEmpty() }!!

    val walletKey: StockKey by lazy { getKeys(KeyType.WALLET).first() }
    val activeKey: StockKey by lazy { getKeys(KeyType.ACTIVE).first() }
    val withdrawKey: StockKey by lazy { getKeys(KeyType.WITHDRAW).first() }
    val infoKey: StockKey by lazy { getKeys(KeyType.HISTORY).first() }

    abstract suspend fun apiRequest(cmd: String, key: StockKey, data: Map<String, Any>? = null, timeOut: Long = 2000): Any?
    abstract suspend fun balance(key: StockKey = infoKey): Map<String, BigDecimal>?
    abstract suspend fun pairInfo(key: StockKey = infoKey): Map<String, TradeFee>?
    abstract suspend fun currencyInfo(key: StockKey = infoKey): Map<String, CrossFee>?
    abstract suspend fun orderInfo(order: Order, updateTotal: Boolean = true): OrderUpdate?
    abstract suspend fun deposit(lastId: Long, transfers: List<Transfer>, key: StockKey = infoKey): Pair<Long, List<TransferUpdate>>
    abstract fun handleError(res: Any): Any?

    private val transferActor = GlobalScope.actor<TransferMsg>(capacity = Channel.UNLIMITED) {
        consumeEach {
            when (it) {
                is TransferList -> updateTransfer(it)
            }
        }
    }

    val updateActor = GlobalScope.actor<BookMsg>(capacity = Channel.UNLIMITED) {
        consumeEach {
            logger.trace { "updateActor: $it" }
            when (it) {
                is InitPair -> initPair(it)
                is SingleUpdate -> singleUpdate(it)
                is UpdateList -> updateBook(it)
                is PairError -> pairError(it)
                is BookError -> bookError()
            }
        }
    }

    fun updateDeposit(cur: String? = null, done: CompletableDeferred<Boolean>? = null) =
        transferActor.offer(TransferList(db.getTransfer(name).let { cur?.run { it.filter { it.cur == cur } } ?: it }, done))

    fun updateActive(update: OrderUpdate, decLock: Boolean) {
        RutEx.controlChannel.offer(ActiveUpdate(name, update, decLock))
    }

    fun infoPolling(block: suspend () -> Unit = EMPTY_LAMBDA) = GlobalScope.launch(handler) {
        var feeTime = LocalDateTime.now()
        var debugTime = LocalDateTime.now()

        while (isActive) {
            try {
                if (ChronoUnit.MINUTES.between(feeTime, LocalDateTime.now()) > 10) {
                    withContext(NonCancellable) {
                        updateCurrencyInfo()
                        updatePairInfo()
                        feeTime = LocalDateTime.now()
                    }
                }

                block.takeIf { it != EMPTY_LAMBDA }?.let { block() }
            } catch (e: Exception) {
                logger.error(e.message, e)
            }
            delay(TimeUnit.SECONDS.toMillis(10))
        }
    }

    override suspend fun updatePairInfo(forceUpdate: Boolean) {
        val lastPair = db.getStockPairs(name) //FIXME: optimize to cache StockPairs in IDb
        var res: Map<String, TradeFee>?

        do {
            res = withContext(NonCancellable) { pairInfo() }?.also {
                it.filter { (pair, p) ->
                    lastPair[pair]?.let { p != it.fee } ?: logger.error("$pair not exists in rutBot").run { false }
                }.takeIf { it.isNotEmpty() }?.let {
                    it.onEach { lastPair[it.key]!!.fee = it.value }
                    UpdateTradeFee(name, it).also { RutEx.controlChannel.send(it) }.done.join()
                }
            }
        } while (takeIf { forceUpdate }?.run { res == null } == true)
    }

    override suspend fun updateCurrencyInfo(forceUpdate: Boolean) {
        val lastCur = db.getStockCurrencies(name) //FIXME: optimize to cache StockCurrencies in IDb
        var res: Map<String, CrossFee>?

        do {
            res = withContext(NonCancellable) { currencyInfo() }?.also {
                it.filter { (cur, c) ->
                    lastCur[cur]?.let { c != it.fee } ?: logger.error("$cur not exists in rutBot").run { false }
                }.takeIf { it.isNotEmpty() }?.let {
                    it.onEach { lastCur[it.key]!!.fee = it.value }
                    UpdateCrossFee(name, if (forceUpdate) lastCur.map { it.key to it.value.fee }.toMap() else it)
                        .also { RutEx.controlChannel.send(it) }.done.join()
                }
            }
        } while (takeIf { forceUpdate }?.run { res == null } == true)
    }

    fun parseJsonResponse(response: String?) = try {
        response?.let {
            logger.trace(response)
            handleError(JSONParser().parse(it))
        } ?: logger.error("null response received").run { null }
    } catch (e: Exception) {
        response?.let {

            Regex(""".*?<title>(.*?)</title>.*""").find(it)?.let {
                logger.error(it.groupValues[1]).run { logger.trace(e.message, e) }.run { logger.trace(response) }
                    .run { null }
            } ?: logger.error(e.message, e).run { logger.error(response) }.run { null }
        }
    }

    override suspend fun getActiveOrders() = GetActiveList(name).also { RutEx.controlChannel.offer(it) }.list.await()

    private fun bookError() {
        internalBook.reset()
        depthChannel.offer(DropBook(name))
    }

    private fun pairError(msg: PairError) {
        internalBook.resetPair(msg.pair)
        depthChannel.offer(DropPair(name, msg.pair))
    }

    fun initPair(data: InitPair) {
        data.pair?.run { internalBook.resetPair(data.pair) } ?: internalBook.reset()
        data.book.forEach {
            internalBook[it.key] = it.value
            it.value.forEach { it.value.nonce++ }
        }
        internalBook.nonce++
        depthChannel.offer(BookUpdate(name, DepthBook(internalBook, depthLimit)))
    }

    private suspend fun singleUpdate(update: SingleUpdate) {
        var depthUpdated = false

        update(update.update)?.run { depthUpdated = true }

        if (depthUpdated) {
            internalBook.nonce ++
            depthChannel.offer(BookUpdate(name, DepthBook(internalBook, depthLimit)))
        } else {
            logger.trace { "skipped update ${internalBook.nonce}" }
        }
    }

    private suspend fun update(upd: Update): Boolean? {
        val index = internalBook.run { upd.amount?.let { updateRate(upd) } ?: removeRate(upd) }

        if (index == null) {
            logger.error { "update falied $upd" }
            depthChannel.offer(DropPair(name, upd.pair))
            reconnectPair(upd.pair)
        } else {
            takeIf { internalBook[upd.pair]!![upd.type]!!.size == 0 }
                ?.run { logger.info { "zero depth in ${upd.pair}(${upd.type})" } }

            if (index >= 0 && index < depthLimit) {
                return true
            }
        }
        return null
    }

    private suspend fun updateBook(msg: UpdateList) {
        var depthUpdated = false

        msg.list.forEach { update(it)?.run { depthUpdated = true } }

        if (depthUpdated) {
            internalBook.nonce ++
            depthChannel.offer(BookUpdate(name, DepthBook(internalBook, depthLimit)))
        } else {
            logger.trace { "skipped update ${internalBook.nonce}" }
        }
    }

    suspend fun shutdown() {
        logger.info("stopping")
        coroutines.onEach { it.cancel() }.forEach { it.join() }
        logger.info("stopped")

        logger.info("saving all nonce's")
        keys.forEach { db.saveNonce(it) }
    }

    private suspend fun updateTransfer(data: TransferList) {
        deposit(lastHistoryId, data.list).let { (historyId, list) ->
            if (historyId > lastHistoryId) {
                db.saveHistoryId(historyId, id)
                lastHistoryId = historyId
            }
            list.forEach { tu ->
                val ts = data.list.find { it.id == tu.id }!!
                if (tu.status != ts.status) {
                    if (ts.status == TransferStatus.SUCCESS) {
//                        onWalletUpdate(plus = Pair(ts.cur, ts.amount))
                        RutEx.controlChannel.send(UpdateWallet(name, plus = Pair(ts.cur, ts.amount)))
                    }
                    db.saveTransfer(ts)
                }
            }
        }
        data.done?.complete(true)
    }

    fun syncWallet() { }
}