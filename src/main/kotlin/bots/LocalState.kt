package bots

import data.*
import database.CrossFee
import database.IDb
import database.OrderStatus
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import mu.KLogger
import org.kodein.di.Kodein
import org.kodein.di.generic.instance
import api.IStock
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.*

class LocalState(override val stockList: Map<String, IStock>, override val logger: KLogger, val kodein: Kodein) : IState {
    val db: IDb by kodein.instance()

    suspend fun onReceive(msg: ControlMsg) {
        ChronoUnit.MILLIS.between(msg.time, LocalDateTime.now())
            .takeIf { it > 1000 }?.let { logger.info("latency: ${it}ms") }

        when (msg) {
            is BookUpdate -> updateBookFull(msg)
            is DropBook -> dropBook(msg)
            is DropPair -> dropPair(msg)
            is UpdateWallet -> updateWallet(msg.name, msg.update, msg.plus, msg.minus)
            is GetWallet -> msg.wallet.complete(stockList[msg.name]!!.wallet.toMap())
            is GetRates -> getRates(msg)
            is UpdateTradeFee -> updateTradeFee(msg)
            is UpdateCrossFee -> updateCrossInfo(msg)
            is GetActiveList -> msg.list.complete(stockList[msg.name]!!.activeList.map { Order(it) })
            is LoadOrder -> loadOrder(msg)
            is ActiveUpdate -> updAct(msg)
        }
    }

    private suspend fun loadOrder(msg: LoadOrder) {
        stockList[msg.name]!!.activeList.addAll(msg.list)
    }

    fun updateCrossFee(stock: String, cur: String, fee: CrossFee) {
        stockList[stock]!!.currencies[cur]!!.fee = fee
    }

    private fun updateCrossInfo(msg: UpdateCrossFee) {
        msg.info.forEach { updateCrossFee(msg.name, it.key, it.value) }
        db.updateCrossFee(msg.info, msg.name)
        logger.info("${msg.name} cross fee updated")
        msg.done.complete(true)
    }

    private fun updateTradeFee(msg: UpdateTradeFee) {
        msg.pairInfo.forEach { stockList[msg.name]!!.pairs[it.key]!!.fee = it.value }
        db.updateTradeFee(msg.pairInfo, msg.name)
        logger.info("${msg.name} trade fee updated")
        msg.done.complete(true)
    }

    private fun dropPair(msg: DropPair) = stockList[msg.name]!!.depthBook.resetPair(msg.pair)

    private fun dropBook(msg: DropBook) = stockList[msg.name]!!.depthBook.reset()

    private suspend fun updateBookFull(msg: BookUpdate) {
        logger.trace {"update ${msg.name}: ${msg.book}" }

        msg.book.let {
            stockList[msg.name]!!.run {
                //                saveState?.run { dbActor.offer(Book(msg.name, msg.time, fullState = it)) }
                val skippedNonce = msg.book.nonce - depthBook.nonce
                if (skippedNonce > 30) {
                    logger.info { "too many nonce skipped: ${depthBook.nonce} -> ${msg.book.nonce} ($skippedNonce)" }
                }
                logger.trace("prevst ${msg.name}: ${depthBook}")
                depthBook.deepReplace(it)
                logger.trace("states ${msg.name}: ${depthBook}")
            }
        }
    }

    private fun updateWallet(stockName: String, update: Map<String, BigDecimal>? = null,
                             plus: Pair<String, BigDecimal>? = null, minus: Pair<String, BigDecimal>? = null) {
        val stock = stockList[stockName]!!
        val total = mutableMapOf<String, BigDecimal>().apply { putAll(stock.wallet) }

        update?.let { total.putAll(it) }
        plus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) + amount) } }
        minus?.let { (cur, amount) -> total.run { put(cur, getOrDefault(cur, BigDecimal.ZERO) - amount) } }
    }

    private fun getRates(msg: GetRates) {
        val data = mutableMapOf<String, MutableMap<String, MutableMap<String, MutableList<Map<String, String>>>>>()
        stockList.forEach { stock, s ->
            s.depthBook.forEach { pair, p ->
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

    private suspend fun updAct(msg: ActiveUpdate) {
        fun logActiveList() {
            logger.info { "--------------------------------------------------------------------------------------" }
            logger.info { "Active list" }
            stockList.forEach { _, s ->  s.activeList.forEach { logger.info { "$it" } } }
        }

        val stock = stockList[msg.name]!!
        val order = stock.activeList.find { if (msg.update.id != null) it.id == msg.update.id else it.stockOrderId == msg.update.orderId }

        if (order == null) {
            logger.error("id: ${msg.update.id} order: ${msg.update.orderId} not found in activeList, possibly it not from bot or already removed")
        } else {

            takeIf { order.stockOrderId == "0" }?.run {
                logger.info("order: ${order.stockOrderId} -> ${msg.update.orderId!!}, deal_id = ${order.id}")
                order.stockOrderId = msg.update.orderId
            }

            msg.update.status?.let {
                logger.info("order: ${order.stockOrderId} status: ${order.status} -> $it")
                order.status = it
            }

            when (order.status) {
                OrderStatus.ACTIVE, OrderStatus.PARTIAL, OrderStatus.COMPLETED -> {
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
                    if (order.attempt < stockList[order.stock]!!.tradeAttemptCount) {
                        val oldId = order.id
                        order.id = Math.abs(UUID.randomUUID().hashCode()).toLong()
                        order.attempt ++
                        order.status = OrderStatus.ACTIVE
                        logger.info("resend to play old id: $oldId new id: ${order.id} attempt: ${order.attempt}")

                        GlobalScope.launch { stockList[order.stock]!!.putOrders(listOf(order)) }
                    } else {
                        logger.info("${stockList[order.stock]!!.tradeAttemptCount} attempts to replay failed, stopping")
                        logger.info("id: ${order.id}, order: ${order.stockOrderId} removing from orderList, status: ${order.status}")
                        stock.activeList.remove(order)
                        logActiveList()
                    }
                }
                OrderStatus.CANCELED -> {
                    logger.info("id: ${order.id}, order: ${order.stockOrderId} was CANCELED removing from orderList, status: ${order.status}")
                    stock.activeList.remove(order)
                    logActiveList()
                }
                OrderStatus.CANCEL_FAILED -> {
                    logger.info { "id: ${order.id}, order: ${order.stockOrderId} canceling failed" }
                }
            }
        }
    }

}