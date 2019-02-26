import api.IState
import api.IStock
import data.LoadOrders
import data.GetRates
import data.GetWallet
import data.Order
import database.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.kodein.di.Kodein
import api.Stock
import api.Transfer
import org.kodein.di.generic.instance
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class StockTest(stockName: String, kodein: Kodein) {
    val state: IState by kodein.instance()

    val stock = state.stockList.getValue(stockName) as Stock

    init {
        runBlocking { stock.start() }
    }

    protected suspend fun getRates() = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(30)) {
        while (isActive) {
            GetRates().also { state.controlChannel.send(it) }.data.await()
                .takeIf { stock.pairs.size == it[stock.name]?.size }
                ?.let { return@withTimeoutOrNull it[stock.name] }
        }
        null
    }

    fun testDepth(pair: String) = runBlocking {
        val state = getRates()
        state?.let { assert(it.containsKey(pair)) } ?: assert(false)
    }

    fun testWallet(cur: String) = runBlocking {
        val wallet = getWallet()

        wallet?.let { assert(it.containsKey(cur)) } ?: assert(false)
    }

    fun testRestWallet(cur: String) = runBlocking {
        val wallet = stock.balance()
        assert(wallet!!.isNotEmpty())
    }

    protected suspend fun getWallet() = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(10)) {
        while (isActive) {
            GetWallet(stock.name).also { state.controlChannel.send(it) }.wallet.await().takeIf { it.isNotEmpty() }
                ?.let { return@withTimeoutOrNull it }
        }
        null
    }

    fun testCurrencyInfo() = runBlocking {
        val curInfo = stock.currencyInfo()!!
        curInfo.forEach {
            if (!stock.currencies.containsKey(it.key)) {
                assert(false)
            }
        }
        assert(true)
    }

    fun testPairInfo() = runBlocking {
        val pairInfo = stock.pairInfo()!!
        val res = pairInfo.containsKey("ltc_btc")
        assert(res)
    }

    fun testOrderLiveCycle(pair: String, type: OperationType) = runBlocking {
        val order = Order(stock.name, type, pair, BigDecimal.ZERO, BigDecimal.ZERO)
        val rates = getRates()!!.also { getWallet() }
        val rate0 = BigDecimal(rates[pair]!![order.book.name]!![0]["rate"])

        val k = BigDecimal("2")
        order.rate = rate0.let { if (order.book == BookType.asks) it / k else it * k }
        order.amount = stock.pairInfo()!![pair]!!.minAmount
        order.summarize_id.complete(0)

        stock.depthBook.incLock(order.pair, order.book)
        state.controlChannel.send(LoadOrders(stock.name, listOf(order)))
        stock.putOrders(listOf(order))

        val orderAfterCreate = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(20)) {
            while (isActive) {
                val list = stock.getActiveOrders()
                list.first { it.id == order.id }.takeIf { it.stockOrderId != "0" }
                    ?.let { return@withTimeoutOrNull it }
            }
            null
        }!!

        assert(orderAfterCreate.status == OrderStatus.ACTIVE)

        val update = stock.orderInfo(orderAfterCreate)

        assert(update!!.status == OrderStatus.ACTIVE)

        stock.cancelOrders(listOf(orderAfterCreate))
        val orderCancelled = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(20)) {
            while (isActive) {
                stock.getActiveOrders().firstOrNull { it.id == order.id } ?: return@withTimeoutOrNull true
            }
            false
        }

        assert(orderCancelled!!)
    }

    fun testDeposit(lastId: Long, transfer: Transfer) = runBlocking {
        stock.getKeys(KeyType.HISTORY).takeIf { it!!.isNotEmpty() }?.let {
            val tl = listOf(transfer)
            val tu = stock.deposit(lastId, tl)
            assert(tu.second.first().status == TransferStatus.SUCCESS)
        } ?: Assume.assumeNotNull(null)
    }

    @AfterAll
    fun saveNonces() {
        stock.keys.forEach { stock.db.saveNonce(it) }
    }
}