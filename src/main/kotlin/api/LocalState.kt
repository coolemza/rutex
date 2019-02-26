package api

import data.*
import data.UpdateWallet
import database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.channels.produce
import kotlinx.coroutines.selects.selectUnbiased
import mu.KLoggable
import mu.KLogger
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.direct
import org.kodein.di.generic.instance
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit.MINUTES
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@ExperimentalUnsignedTypes
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
class LocalState(override val kodein: Kodein) : IState, KLoggable, KodeinAware {
    override val logger = this.logger("RutBot")

    private val db: IDb by instance()

    private val handler = CoroutineExceptionHandler { _, e -> logger.error(e.message, e) }

    override lateinit var stockList: Map<String, IStock>

    override val controlChannel = GlobalScope.actor<ControlMsg>(capacity = Channel.UNLIMITED) {
        for (msg in channel) {
            when (msg) {
                is UpdateWallet -> updateWallet(msg.name, msg.update, msg.plus, msg.minus)
                is GetWallet -> msg.wallet.complete(stockList.getValue(msg.name).walletAvailable.toMap())
                is GetRates -> getFullState(msg)
                is DebugWallet -> stockList.getValue(msg.name).debugWallet.putAll(msg.update)
                is UpdateTradeFee -> updateTradeFee(msg)
                is UpdateCrossFee -> updateCrossInfo(msg)
                is InitFeeVal -> initFeeVal(msg)
                is GetActiveList -> msg.list.complete(stockList.getValue(msg.name).activeList.map { Order(it) })
                is LoadOrders -> loadOrder(msg)
                is ActiveUpdate -> updateActiveList(msg)
            }
        }
    }

    private val botsChannel = GlobalScope.launch(context = Executors.newFixedThreadPool(1).asCoroutineDispatcher()) {
        val stockNonce = ULongArray(StockId.values().size) { 0u }
        val latencySum = ULongArray(StockId.values().size) { 0u }
        val latencyCount = ULongArray(StockId.values().size) { 0u }
        var nonce = 0L

        val selectList = stockList.map { it.value.depthChannel }

        while (isActive) {
            selectUnbiased<Unit> {
                selectList.forEach { channel ->
                    channel.onReceive { update ->
                        when (update) {
                            is FullBook -> {
                                val latency = update.nonce - stockNonce[update.stock.id]
                                latencySum[update.stock.id] += latency
                                latencyCount[update.stock.id] ++
                                stockNonce[update.stock.id] = update.nonce

                                if ( latency > 20u ) {
                                    logger.info { "${update.stock.name}: latency $latency" }
                                }
                                update.stock.depthBook = update.book

                                val stateCopy = stockList.entries.associateBy({ it.key }) { it.value.depthBook }

                                FullState(nonce ++, update.stock, stateCopy).let {
                                    currentState.set(it)
                                }
                            }
                            is DropBook -> update.stock.depthBook.reset()
                            is DropPair -> update.stock.depthBook.resetPair(update.pair)
                        }
                    }
                }
            }
        }
    }

    val currentState = AtomicReference<FullState>()

    private suspend fun allStatesReady() = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(60)) {
        while (isActive) {
            GetRates().also { controlChannel.send(it) }.data.await()
                .takeIf { rates ->  stockList.all { it.value.pairs.size == rates[it.key]?.size } }
                ?.let { return@withTimeoutOrNull true }
        }
        null
    }

    override suspend fun start(startBots: Boolean) {
        stockList = kodein.direct.instance<Set<String>>("stocks")
            .associateBy({ it }) { kodein.direct.instance<IStock>(tag = StockId.valueOf(it)) }

        logger.info("**************************************** Updating Fees ******************************************")
        coroutineScope {
            stockList.forEach {
                launch(handler) {
                    it.value.updateCurrencyInfo(forceUpdate = true)
                    it.value.updatePairInfo(forceUpdate = true)
                }
            }
        }

        InitFeeVal().also { controlChannel.send(it) }.done.join()

        logger.info("**************************************** Starting api *******************************************")
        stockList.forEach { GlobalScope.launch(handler) { it.value.start() } }
    }

    override suspend fun stop() {
        logger.warn("stopping RutBot..")

        coroutineScope {
            stockList.forEach { launch(handler) { it.value.stop() } }
        }

        botsChannel.cancelAndJoin()

        logger.info("RutBot stopped")
    }

    private suspend fun loadOrder(msg: LoadOrders) {
        stockList.getValue(msg.name).activeList.addAll(msg.list)
    }

    private fun updateTradeFee(msg: UpdateTradeFee) {
        msg.pairInfo.forEach { stockList.getValue(msg.name).pairs.getValue(it.key).fee = it.value }
        db.updateTradeFee(msg.pairInfo, msg.name)
        logger.info("${msg.name} trade fee updated")
        msg.done.complete(true)
    }

    private fun getFeeVal(fee: Fee, limit: BigDecimal): BigDecimal {
        var feeVal = BigDecimal(BigInteger.ZERO, MathContext(12, RoundingMode.HALF_EVEN))

        if (fee.min.compareTo(BigDecimal.ZERO) != 0) {
            feeVal = fee.min
            if (fee.percent.compareTo(BigDecimal.ZERO) != 0) {
                val percent = limit * fee.percent
                feeVal = if (fee.min > percent) fee.min else percent
            }
        } else if (fee.percent.compareTo(BigDecimal.ZERO) != 0) {
            feeVal = limit * fee.percent
        }
        return feeVal
    }

    private fun updateAllFee(stockFrom: String, stockTo: String, cur: String, balance: Map<PlayType, Balance>) {
        val withdrawFee = getFeeVal(stockList.getValue(stockFrom).currencies.getValue(cur).fee.withdrawFee,
            balance.getValue(PlayType.LIMIT).limit)
        val depositFee = getFeeVal(stockList.getValue(stockTo).currencies.getValue(cur).fee.depositFee,
            balance.getValue(PlayType.LIMIT).limit - withdrawFee)
        val withdrawFeeFull = getFeeVal(stockList.getValue(stockFrom).currencies.getValue(cur).fee.withdrawFee,
            balance.getValue(PlayType.FULL).limit)
        val depositFeeFull = getFeeVal(stockList.getValue(stockTo).currencies.getValue(cur).fee.depositFee,
            balance.getValue(PlayType.FULL).limit - withdrawFeeFull)
        balance.getValue(PlayType.LIMIT).feeVal = withdrawFee + depositFee
        balance.getValue(PlayType.FULL).feeVal = withdrawFeeFull + depositFeeFull
    }

    private fun initFeeVal(msg: InitFeeVal) {
        stockList.forEach { stockFrom, sf ->
            sf.limit.forEach { stockTo, st ->
                st.forEach { cur, balance ->
                    updateAllFee(stockFrom, stockTo, cur, balance)
                }
            }
            sf.limitLess.forEach { _, st ->
                st.forEach { _, balance ->
                    balance.forEach { it.value.feeVal = BigDecimal.ZERO }
                }
            }
        }
        msg.done.complete(true)
    }

    private fun updateCrossFee(stock: String, cur: String, fee: CrossFee) {
        stockList.getValue(stock).currencies.getValue(cur).fee = fee

        // if deposit fee changed
        stockList.getValue(stock).limit.forEach { stockTo, st ->
            st.filterKeys { it == cur }.forEach { cur, balance -> updateAllFee(stock, stockTo, cur, balance) }
        }

        // if withdraw fee changed
        stockList.forEach { _, sf ->
            sf.limit.filter { it.key == stock }.forEach { stockTo, st ->
                st.filterKeys { it == cur }.forEach { cur, balance -> updateAllFee(stock, stockTo, cur, balance) }
            }
        }
    }

    private fun updateCrossInfo(msg: UpdateCrossFee) {
        msg.info.forEach { updateCrossFee(msg.name, it.key, it.value) }
        db.updateCrossFee(msg.info, msg.name)
        logger.info("${msg.name} cross fee updated")
        msg.done.complete(true)
    }

    private fun getFullState(msg: GetRates) {
        val data = mutableMapOf<String, MutableMap<String, MutableMap<String, MutableList<Map<String, String>>>>>()
        currentState.get()?.books?.forEach { stock, s ->
            s.forEach { pair, p ->
                p.forEach { type, t ->
                    t.forEach {
                        data.getOrPut(stock) { mutableMapOf() }
                            .getOrPut(pair) { mutableMapOf() }
                            .getOrPut(type.name) { mutableListOf() }
                            .add(mapOf("rate" to it.rate.toString(), "amount" to it.amount.toString()))
                    }
                }
            }
        }
        msg.data.complete(data)
    }

    private fun logActiveList() {
        logger.info { "--------------------------------------------------------------------------------------" }
        logger.info { "Active list" }
        stockList.forEach { _, s ->  s.activeList.forEach { logger.info { "$it" } } }
    }

    private suspend fun updateActiveList(msg: ActiveUpdate) {
        val stock = stockList.getValue(msg.name)
        val order = stock.activeList.find { if (msg.update.id != null) it.id == msg.update.id else it.stockOrderId == msg.update.orderId }

        if (order == null) {
            logger.error("id: ${msg.update.id} order: ${msg.update.orderId} not found in activeList, possibly it not from bot or already removed")
        } else {
            takeIf { msg.decLock }?.run {
                val lock = stock.depthBook.decLock(order.pair, order.book)
                logger.info { "$stock: ${order.pair}(${order.book}) lock decreased to: $lock" }
            }

            msg.update.status?.let {
                logger.info("order: ${order.stockOrderId} status: ${order.status} -> $it")
                order.status = it
            }

            when (order.status) {
                OrderStatus.ACTIVE, OrderStatus.PARTIAL, OrderStatus.COMPLETED -> {
                    if ( order.stockOrderId == "0" ) {
                        logger.info("order: ${order.stockOrderId} -> ${msg.update.orderId!!}, deal_id = ${order.id}")
                        order.stockOrderId = msg.update.orderId
                    }

                    if (msg.update.amount != null) {
                        val newRemaining = order.remaining - msg.update.amount
                        logger.info("order: ${order.stockOrderId} remaining: ${order.remaining} -> $newRemaining amount: ${msg.update.amount}, status: ${order.status}")
                        order.remaining = newRemaining
                        val complete = order.remaining.compareTo(BigDecimal.ZERO) == 0

                        if (complete) {
                            order.status = OrderStatus.COMPLETED
                            logger.info("id: ${order.id} order: ${order.stockOrderId} removed from orderList, status: ${order.status}")
                            stock.activeList.remove(order)
                            logActiveList()
                        }
                    }
                }
                OrderStatus.FAILED -> {
                    if (order.attempt < stockList.getValue(order.stock).tradeAttemptCount) {
                        val oldId = order.id
                        order.id = Math.abs(UUID.randomUUID().hashCode()).toLong()
                        order.attempt ++
                        order.status = OrderStatus.ACTIVE
                        logger.info("resend to play old id: $oldId new id: ${order.id} attempt: ${order.attempt}")

                        GlobalScope.launch { stockList.getValue(order.stock).putOrders(listOf(order)) }
                    } else {
                        logger.info("${stockList.getValue(order.stock).tradeAttemptCount} attempts to replay failed, stopping")
                        logger.info("id: ${order.id}, order: ${order.stockOrderId} removing from orderList, status: ${order.status}")
                        stock.activeList.remove(order)
                        logActiveList()
                        updateWallet(msg.name)
                    }
                }
                OrderStatus.CANCELED -> {
                    logger.info("id: ${order.id}, order: ${order.stockOrderId} was CANCELED removing from orderList, status: ${order.status}")
                    stock.activeList.remove(order)
                    logActiveList()
                    updateWallet(msg.name)
                }
                OrderStatus.CANCEL_FAILED -> {
                    logger.info { "id: ${order.id}, order: ${order.stockOrderId} canceling failed" }
                }
            }
        }
    }

    private fun updateWallet(stockName: String, update: Map<String, BigDecimal>? = null,
                             plus: Pair<String, BigDecimal>? = null, minus: Pair<String, BigDecimal>? = null) {
        val stock = stockList.getValue(stockName)
        val total = mutableMapOf<String, BigDecimal>().apply { putAll(stock.walletTotal) }

        update?.let { total.putAll(it) }
        plus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) + amount) } }
        minus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) - amount) } }

        val locked = stock.getLocked(stock.activeList)
        val played = stock.getPlayedAmount(stock.activeList)

        val available = total.entries.associateBy({ it.key }) { it.value - locked.getOrDefault(it.key, BigDecimal.ZERO) }
        val totalPlayed = available.entries.associateBy({ it.key }) { it.value + played.getOrDefault(it.key, BigDecimal.ZERO) }

        stock.walletAvailable.apply { putAll(available) }.onEach { stock.walletWithRatio[it.key] = it.value * stock.walletRatio }
            .let { logger.info { "$stockName: avaBot - ${it.toSortedMap()}" } }
        logger.info { "$stockName - avaRat: ${stock.walletWithRatio.toSortedMap()}" }
        logger.info("$stockName - avaDBG: ${stock.debugWallet.toSortedMap()}")

        stock.walletTotal.apply { putAll(total) }.let { logger.info { "total: $it" } }
        stock.walletLocked.apply { clear(); putAll(locked) }.let { logger.info { "locked: $it" } }
        stock.walletWithRatio.let { logger.info { "available: $it" } }
        stock.walletTotalPlayed.apply { putAll(totalPlayed) }
    }
}