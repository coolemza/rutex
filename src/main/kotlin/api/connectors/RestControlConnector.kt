package api.connectors

import api.OrderUpdate
import data.Order
import database.OrderStatus
import database.StockKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KLogger
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance
import utils.IHttp
import java.math.BigDecimal

class RestControlConnector(val tradeKeys: List<StockKey>?, override val kodein: Kodein, val logger: KLogger,
                           val handler: suspend (OrderUpdate, Boolean) -> Unit,
                           val privateApi: suspend (String, StockKey, Map<String, Any>?) -> Pair<Map<String, String>, String>
) : IConnector, KodeinAware {
    val http: IHttp by instance()

    private val tradeLock = Mutex()

    override suspend fun start() {}

    override suspend fun stop() {}

    suspend fun apiRequest(cmd: Pair<String, String>, key: StockKey?, data: Map<String, Any>?, timeOut: Long = 2000): String? {
        return key?.let {
            val (url, urlParam) = cmd
            val (headers, postData) = privateApi(urlParam, key, data)
            http.post(logger, url, headers, postData, timeOut)
        }
    }

    suspend fun post(url: String, headers: Map<String, String>?, postData: String, timeOut: Long = 2000): String? {
        return http.post(logger, url, headers, postData, timeOut)
    }

    override suspend fun reconnect() {}

    private suspend fun getTradeKeys(orders: List<Order>) = tradeLock.withLock {
        //FIXME: remove lock
        tradeKeys?.filter { !it.busy }?.takeIf { it.size >= orders.size }?.let { key ->
            orders.mapIndexed { i, order -> order to key[i] }.onEach { it.second.busy = true }
        } ?: orders.forEach {
            handler(OrderUpdate(it.id, it.stockOrderId, BigDecimal.ZERO, OrderStatus.FAILED), true)
        }.also { logger.error("not enough threads for Trading!!!") }.let { null }
    }

    private suspend fun releaseTradeKey(key: StockKey) = tradeLock.withLock { key.busy = false }

    suspend fun parallelOrders(orders: List<Order>, code: suspend (Order, StockKey) -> OrderUpdate) {
        getTradeKeys(orders)?.map { (order, key) ->
            GlobalScope.async(Dispatchers.IO) { code(order, key).also { releaseTradeKey(key) } }
        }?.map { it.await() }?.forEach { handler(it, true) }
    }
}