package api

import Parameters
import data.*
import data.UpdateWallet
import database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import mu.KLoggable
import org.json.simple.parser.JSONParser
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.direct
import org.kodein.di.generic.instance
import org.kodein.di.generic.instanceOrNull
import org.kodein.di.newInstance
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@ExperimentalUnsignedTypes
@ObsoleteCoroutinesApi
abstract class Stock(final override val kodein: Kodein, final override val name: String) : IStock, KodeinAware, KLoggable {
    override val id = StockId.valueOf(name).ordinal

    val db: IDb by instance()
    private val runningStocks: Set<String> by kodein.instance("stocks") //FIXME: refactor this

    val state: IState by instance()

    override val saveState: Boolean? =
        direct.newInstance { instanceOrNull<Set<String>>(Parameters.saveState)?.takeIf { it.contains(name) }?.let { true } }
    private val debug: Boolean? =
        direct.newInstance { instanceOrNull<List<String>>(Parameters.debug)?.takeIf { it.contains(name) }?.let { true } }

    override val logger = this.logger(name)

    override val limit: CrossBalance = db.getCrossLimits(name, runningStocks)
    override val limitLess: CrossBalance = db.getCrossLimits(name, runningStocks, true)

    val userAgent = "Chrome/33.0.1750.152 Safari/537.36"
    val info = db.getStockInfo(name)

    val keys = db.getKeys(name, direct.instance(Parameters.key))
    override val pairs = db.getStockPairs(name)
    override val currencies = db.getStockCurrencies(name)
    override val walletRatio = info.ratio
    override val activeList = mutableListOf<Order>()

    protected val coroutines = mutableListOf<Job>()
    private val EMPTY_LAMBDA: suspend () -> Unit = {}

    private var lastHistoryId = info.historyId
    override val debugWallet = mutableMapOf<String, BigDecimal>()

    override var depthLimit = 5
    override val tradeAttemptCount = 30

    protected val internalBook = DepthBook()
    override var depthBook = DepthBook()
    override val depthChannel = Channel<FullBookMsg>(capacity = Channel.CONFLATED)

    override val walletWithRatio = mutableMapOf<String, BigDecimal>()
    override val walletFull = db.getTestWallet(name)

    override val walletAvailable = mutableMapOf<String, BigDecimal>()
    override val walletLocked = mutableMapOf<String, BigDecimal>()
    override val walletTotal = mutableMapOf<String, BigDecimal>()
    override val walletTotalPlayed = mutableMapOf<String, BigDecimal>()

    fun getKeys(type: KeyType) = keys.filter { it.type == type }.takeIf { it.isNotEmpty() }

    val walletKey = getKeys(KeyType.WALLET)?.first()
    val activeKey = getKeys(KeyType.ACTIVE)?.first()
    val withdrawKey = getKeys(KeyType.WITHDRAW)?.first()
    val infoKey = getKeys(KeyType.HISTORY)?.first()

    abstract suspend fun balance(key: StockKey? = infoKey): Map<String, BigDecimal>?
    abstract suspend fun pairInfo(key: StockKey? = infoKey): Map<String, TradeFee>?
    abstract suspend fun currencyInfo(key: StockKey? = infoKey): Map<String, CrossFee>?
    abstract suspend fun orderInfo(order: Order, updateTotal: Boolean = true): OrderUpdate?
    abstract suspend fun deposit(lastId: Long, transfers: List<Transfer>, key: StockKey? = infoKey): Pair<Long, List<TransferUpdate>>
    abstract fun handleError(res: Any): Any?

    private val transferActor = GlobalScope.actor<TransferMsg>(Dispatchers.IO, Channel.UNLIMITED) {
        consumeEach {
            when (it) {
                is TransferList -> updateTransfer(it)
            }
        }
    }

//    private val updateActor = GlobalScope.actor<BookMsg>(Dispatchers.Default, Channel.UNLIMITED) {
//        consumeEach {
//            logger.trace { "updateActor: $it" }
//            when (it) {
//                is InitPair -> initPair(it.pair, it.book)
//                is SingleUpdate -> singleUpdate(it)
//                is UpdateList -> updateBook(it)
//                is PairError -> pairError(it)
//                is BookError -> dropBook()
//                is ResetBook -> dropBook()
//            }
//        }
//    }

//    suspend fun updateBook(msg: BookMsg) = updateActor.send(msg)

    fun updateDeposit(cur: String? = null, done: CompletableDeferred<Boolean>? = null) {
        transferActor.offer(TransferList(db.getTransfer(name).let { transfer ->
            cur?.run { transfer.filter { it.cur == cur } } ?: transfer
        }, done))
    }

    override fun updateActive(update: OrderUpdate, decLock: Boolean) = state.controlChannel.offer(ActiveUpdate(name, update, decLock))
    override fun updateState(update: ControlMsg) = state.controlChannel.offer(update)

    fun updateWallet(update: UpdateWallet) = state.controlChannel.offer(update)

    private fun infoPolling(block: suspend () -> Unit = EMPTY_LAMBDA) = GlobalScope.launch(Dispatchers.IO) {
        var feeTime = LocalDateTime.now()
        var debugTime = LocalDateTime.now()

        while (isActive) {
            try {
                if (ChronoUnit.MINUTES.between(feeTime, LocalDateTime.now()) > 10) {
                    updateCurrencyInfo()
                    updatePairInfo()
                    feeTime = LocalDateTime.now()
                }
                takeIf { debug == true }?.run {
                    if (ChronoUnit.SECONDS.between(debugTime, LocalDateTime.now()) > 10) {
                        balance()?.let { state.controlChannel.send(DebugWallet(name, it)) }
                        debugTime = LocalDateTime.now()
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
            res =  pairInfo()?.also { pi ->
                pi.filter { (pair, p) ->
                    lastPair[pair]?.let { p != it.fee } ?: logger.error("$pair not exists in rutBot").run { false }
                }.takeIf { it.isNotEmpty() }?.let { pinfo ->
                    pinfo.onEach { lastPair.getValue(it.key).fee = it.value }
                    UpdateTradeFee(name, pinfo).also { state.controlChannel.send(it) }.done.join()
                }
            }
        } while (takeIf { forceUpdate }?.run { res == null } == true)
    }

    override suspend fun updateCurrencyInfo(forceUpdate: Boolean) {
        val lastCur = db.getStockCurrencies(name) //FIXME: optimize to cache StockCurrencies in IDb
        var res: Map<String, CrossFee>?

        do {
            res = currencyInfo()?.also { ci ->
                ci.filter { (cur, c) ->
                    lastCur[cur]?.let { c != it.fee } ?: logger.error("$cur not exists in rutBot").run { false }
                }.takeIf { it.isNotEmpty() }?.let { cInfo ->
                    cInfo.onEach { lastCur.getValue(it.key).fee = it.value }
                    UpdateCrossFee(name, if (forceUpdate) lastCur.map { it.key to it.value.fee }.toMap() else cInfo)
                        .also { state.controlChannel.send(it) }.done.join()
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
        response?.let { res ->

            Regex(""".*?<title>(.*?)</title>.*""").find(res)?.let {
                logger.error(it.groupValues[1]).run { logger.trace(e.message, e) }.run { logger.trace(response) }
                    .run { null }
            } ?: logger.error(e.message, e).run { logger.error(response) }.run { null }
        }
    }

    override suspend fun getActiveOrders() = GetActiveList(name).also { state.controlChannel.offer(it) }.list.await()

    private fun dropBook() {
        internalBook.reset()
        depthChannel.offer(DropBook(this))
    }

    protected fun pairError(pair: String) {
        internalBook.resetPair(pair)
        depthChannel.offer(DropPair(this, pair))
    }

    protected fun initPair(pair: String?, update: List<Update>) {
        pair?.run { internalBook.resetPair(pair) } ?: internalBook.reset()

        update.forEach {
            internalBook.getOrPut(it.pair) { DepthType() }.getOrPut(it.type) { DepthList() }
                .add(Depth(it.rate, it.amount!!))
        }

        internalBook[pair]!!.forEach { it.value.nonce ++ }
        internalBook.nonce ++
        depthChannel.offer(FullBook(this, DepthBook(internalBook, depthLimit), internalBook.nonce))
    }

    protected fun initPair(pair: String?, book: DepthBook) {
        pair?.run { internalBook.resetPair(pair) } ?: internalBook.reset()
        book.forEach { bookPair, p ->
            internalBook[bookPair] = p
            p.forEach { it.value.nonce++ }
        }
        internalBook.nonce ++
        depthChannel.offer(FullBook(this, DepthBook(internalBook, depthLimit), internalBook.nonce))
    }

    protected fun singleUpdate(update: Update) {
        var depthUpdated = false

        update(update)?.run { depthUpdated = true }

        if (depthUpdated) {
            internalBook.nonce ++
            depthChannel.offer(FullBook(this, DepthBook(internalBook, depthLimit), internalBook.nonce))
        } else {
            logger.trace { "skipped update ${internalBook.nonce}" }
        }
    }

    private fun update(upd: Update): Boolean? {
        val index = internalBook.run { upd.amount?.let { updateRate(upd) } ?: removeRate(upd) }

        return index?.let {
            takeIf { internalBook[upd.pair]!![upd.type]!!.size == 0 }
                ?.run { logger.info { "zero depth in ${upd.pair}(${upd.type})" } }

            takeIf { index in 0..(depthLimit - 1) }?.let { true }
//            if (index in 0..(depthLimit - 1)) {
//                return true
//            }
        }
//        if (index == null) {
//            logger.error { "update falied $upd" }
//            depthChannel.offer(DropPair(this, upd.pair))
//            reconnectPair(upd.pair)
//        } else {
//            takeIf { internalBook[upd.pair]!![upd.type]!!.size == 0 }
//                ?.run { logger.info { "zero depth in ${upd.pair}(${upd.type})" } }
//
//            if (index in 0..(depthLimit - 1)) {
//                return true
//            }
//        }
//        return null
    }

    protected fun updateBook(list: List<Update>) {
        var depthUpdated = false

        list.forEach { update(it)?.run { depthUpdated = true } }

        if (depthUpdated) {
            internalBook.nonce ++
            depthChannel.offer(FullBook(this, DepthBook(internalBook, depthLimit), internalBook.nonce))
        } else {
            logger.trace { "skipped update ${internalBook.nonce}" }
        }
    }

//    override suspend fun reconnectPair(pair: String) = bookConnectors.forEach { it.reconnect() }

    override suspend fun start() {
        syncWallet()
        coroutines.add(infoPolling())
    }

    override suspend fun stop() {
        logger.info("stopping")
        coroutines.forEach {
            logger.info("cancelling $it")
            it.cancel()
        }
        coroutines.forEach {
            it.join()
            logger.info("joined $it")
        }
        logger.info("stopped")

        logger.info("saving all nonce's")
        keys.forEach { db.saveNonce(it) }
    }

    suspend fun syncWallet() {
        updateWallet(UpdateWallet(name, update = balance()))
    }

    private suspend fun updateTransfer(data: TransferList) {
        deposit(lastHistoryId, data.list).let { (historyId, list) ->
            if (historyId > lastHistoryId) {
                db.saveHistoryId(historyId, name)
                lastHistoryId = historyId
            }
            list.forEach { tu ->
                val ts = data.list.find { it.id == tu.id }!!
                if (tu.status != ts.status) {
                    if (ts.status == TransferStatus.SUCCESS) {
//                        onWalletUpdate(plus = Pair(ts.cur, ts.amount))
                        state.controlChannel.send(UpdateWallet(name, plus = Pair(ts.cur, ts.amount)))
                    }
                    db.saveTransfer(ts)
                }
            }
        }
        data.done?.complete(true)
    }
}