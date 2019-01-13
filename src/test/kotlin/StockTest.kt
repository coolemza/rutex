import api.RutexStock

import data.LoadOrder
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
import api.Transfer
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.reflect.full.primaryConstructor

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
open class StockTest(name: String, kodein: Kodein) {

    val stock: RutexStock = Class.forName("api.$name").kotlin.primaryConstructor?.call(kodein) as RutexStock

    init {
        RutEx.stockList[name] = stock
        runBlocking { stock.start() }
    }

    protected suspend fun getRates() = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(200)) {
        while (isActive) {
            GetRates().also { RutEx.controlChannel.send(it) }.data.await()
                .takeIf { stock.pairs.size == it[stock.name]?.size }
                ?.let { return@withTimeoutOrNull it[stock.name] }
        }
        null
    }

    fun testDepth(pair: String) = runBlocking {
        val state = getRates() //FIXME: test not onyl pair presecnce
//        depth?.forEach {
//            (it.value as Map<*, *>).forEach {
//                if (!((it.key as BookType).toString().trim().contains("bids") || (it.key as BookType).toString().trim().contains("asks")))
//                    status = false
//            }
//        }
        state?.let { assert(it.containsKey(pair)) } ?: assert(false)
    }

    fun testWallet(cur: String) = runBlocking {
        val wallet = getWallet()
        wallet?.let { assert(it.containsKey(cur)) } ?: assert(false)
    }

    fun testRestWallet(cur: String) = runBlocking {
        val gg = stock.balance()
        val ggg = gg
        val dd = ggg
    }

    protected suspend fun getWallet() = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(10)) {
        while (isActive) {
            GetWallet(stock.name).also { RutEx.controlChannel.send(it) }.wallet.await().takeIf { it.isNotEmpty() }
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
        val state = getRates()!!.also { getWallet() }
        val rate0 = BigDecimal(state[pair]!![order.book.name]!![0]["rate"])

        val k = BigDecimal("2")
        order.rate = rate0.let { if (order.book == BookType.asks) it / k else it * k }
        order.amount = stock.pairInfo()!![pair]!!.minAmount

        stock.depthBook.incLock(order.pair, order.book)
        RutEx.controlChannel.send(LoadOrder(stock.name, listOf(order)))
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
        stock.getKeys(KeyType.HISTORY).takeIf { it.isNotEmpty() }?.let {
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
//class StockTest {
//
//    @Test fun StockTrade() {
////        Trade("Bitfinex")
////        getHistory()
//        Assert.assertEquals(true, true)
//    }
//
////    @Test fun removeFailed() {
////        val stock = Bitfinex()
////
////        val o1 = Order(id=1169121433, summarize_id=0, order_id=0, type="buy", pair="usd_rur", rate=BigDecimal("57.48"),
////                amount=BigDecimal("62.90676743"), remaining = BigDecimal("62.90676743"), have=BigDecimal("3615.88018282"),
////                willGet=BigDecimal("62.78095389"), fee=BigDecimal("0.998"), crossFeeVal=BigDecimal("0"), stock="WEX", status=OrderStatus.ACTIVE)
////        val o2 = Order(id=367351396, summarize_id=0, order_id=0, type="sell", pair="eth_rur", rate=BigDecimal("14500"),
////                amount=BigDecimal("0.24987076"), remaining = BigDecimal("0.24987076"), have=BigDecimal("0.24987076"),
////                willGet=BigDecimal("3615.88010195"), fee=BigDecimal("0.998"), crossFeeVal=BigDecimal("0"), stock="WEX", status=OrderStatus.ACTIVE)
////        val o3 = Order(id=1282452642, summarize_id=0, order_id=0, type="buy", pair="eth_usd", rate=BigDecimal("250.50002"),
////                amount=BigDecimal("0.25046148"), remaining = BigDecimal("0.25046148"), have=BigDecimal("62.74060869"),
////                willGet=BigDecimal("0.24996057"), fee=BigDecimal("0.998"), crossFeeVal=BigDecimal("0"), stock="WEX", status=OrderStatus.ACTIVE)
////
////        stock.activeList = mutableListOf(o1, o2, o3)
////
////        stock.onActive(1169121433, 666, BigDecimal.ZERO, OrderStatus.FAILED)
////
////        Assert.assertEquals(stock.activeList.size, 2)
////    }
//
//    @Test fun onActive () {
// //       val stock = Bitfinex()
//
////        val stock = WEX()
////        val o1 = Order(id=1427182842, summarize_id=179, order_id=1858356303, type="sell", pair="eth_rur", rate=BigDecimal("19749.75295000"),
////                amount=BigDecimal("0.23221974"), remaining=BigDecimal("0.23221974"), have=BigDecimal("0.23221974"), willGet=BigDecimal("4577.13738106"),
////                fee=BigDecimal("0E-8"), crossFeeVal=BigDecimal("0E-8"), stock="WEX", status=OrderStatus.ACTIVE, attempt=0)
//////        val o1 = Order(id=427747493, summarize_id=451, order_id=1858772019, type="buy", pair="eth_btc", rate=BigDecimal("0.11383"),
//////                amount=BigDecimal("0.02111350"), remaining=BigDecimal("0.02111350"), have=BigDecimal("0.00240334"),
//////                willGet=BigDecimal("0.02107127"), fee=BigDecimal("0.998"), crossFeeVal= BigDecimal.ZERO, stock="WEX", status=OrderStatus.ACTIVE, attempt=0)
//////        val o1 = Order(id=1169121433, summarize_id=0, order_id=0, type="buy", pair="usd_rur", rate=BigDecimal("57.48"), amount=BigDecimal("62.90676743"), have=BigDecimal("3615.88018282"), willGet=BigDecimal("62.78095389"), fee=BigDecimal("0.998"), crossFeeVal=BigDecimal("0"), stock="WEX", status=OrderStatus.ACTIVE)
//////        val o2 = Order(id=367351396, summarize_id=0, order_id=0, type="sell", pair="eth_rur", rate=BigDecimal("14500"), amount=BigDecimal("0.24987076"), have=BigDecimal("0.24987076"), willGet=BigDecimal("3615.88010195"), fee=BigDecimal("0.998"), crossFeeVal=BigDecimal("0"), stock="WEX", status=OrderStatus.ACTIVE)
//////        val o3 = Order(id=1282452642, summarize_id=0, order_id=0, type="buy", pair="eth_usd", rate=BigDecimal("250.50002"), amount=BigDecimal("0.25046148"), have=BigDecimal("62.74060869"), willGet=BigDecimal("0.24996057"), fee=BigDecimal("0.998"), crossFeeVal=BigDecimal("0"), stock="WEX", status=OrderStatus.ACTIVE)
////
////        stock.activeList.clear()
////
////        stock.activeList.putAll(mapOf(o1.id to o1/*, o2.id to o2, o3.id to o3*/))
////        //{btc=0.30720771, ppc=0, eur=13.01639544, nmc=0.0054, usd=276.73589702, dsh=0, eth=0.25432555, ltc=1.40521403, nvc=0, rur=7125.18748630}
////        stock.walletTotal.putAll(mapOf("btc" to BigDecimal("0.30720771"), "ltc" to BigDecimal("40521403"), "rur" to BigDecimal("7125.18748630"), "eth" to BigDecimal("25432555")))
////
////        //stock.walletTotal.putAll(mapOf("eth" to BigDecimal("0.60128975"), "usd" to BigDecimal("628.09404413"), "rur" to BigDecimal("4017.64464759")))
////
////        stock.onActive(1427182842, 1234, BigDecimal("0.23221974"), OrderStatus.COMPLETED)
//        Assert.assertEquals(true, true)
//    }
//
////    @Test
////    fun getBalance() {
////        val stock = Kraken()
////        stock.keys = RutDb.GetApiKeys(stock.name, RutBot.cfg["key"]!! as String)
////
////        stock.walletKey = stock.keys.find { it.type == "WALLET" }!!
////
////        stock.getBalance()
////    }
//
////    @Test
////    fun deposit() {
////        val stock = Poloniex()
////        stock.activeKey = RutDb.GetApiKeys(stock.name, RutBot.cfg["key"]!! as String).find { it.type == "ACTIVE" }!!
////
////        val start = SimpleDateFormat("yyyy-MM-dd").parse("2016-09-31").toInstant()
////        val cc = stock.depositWithdrawls("1476981068".toLong())
////    }
////
////    @Test fun order() {
//////        "orderNumber" -> 124055420549
////        val stock = Poloniex()
////        stock.activeKey = RutDb.GetApiKeys(stock.name, RutBot.cfg["key"]!! as String).find { it.type == "ACTIVE" }!!
////
////        val o1 = Order(id=1427182842, summarize_id=1000, order_id=126111333578, type="buy", pair="btc_usdt", rate=BigDecimal("250"),
////                amount=BigDecimal("0.23221974"), remaining=BigDecimal("0.23221974"), have=BigDecimal("0.23221974"), willGet=BigDecimal("4577.13738106"),
////                fee=BigDecimal("0E-8"), crossFeeVal=BigDecimal("0E-8"), stock="WEX", status=OrderStatus.ACTIVE, attempt=0)
////
////        stock.OrderInfo(RequestTimeout(500), o1, false)
////    }
////
////    @Test
////    fun active() {
////        val stock = Poloniex()
////        stock.activeKey = RutDb.GetApiKeys(stock.name, RutBot.cfg["key"]!! as String).find { it.type == "ACTIVE" }!!
////
////        stock.poloActive()
////
////            delay(1000000000000000)
////
//////        launch(CommonPool) {
//////            stock.poloActive()
//////        }
////    }
////
////    @Test
////    fun trade() {
////        val stock = Poloniex()
////
////        stock.activeKey = RutDb.GetApiKeys(stock.name, RutBot.cfg["key"]!! as String).find { it.type == "ACTIVE" }!!
////
////        val o1 = Order(id=1427182842, summarize_id=1000, order_id=0, type="buy", pair="eth_usdt", rate=BigDecimal("250"),
////                amount=BigDecimal("0.01"), remaining=BigDecimal("0.01"), have=BigDecimal("2.5"), willGet=BigDecimal("0.01"),
////                fee=BigDecimal("0E-8"), crossFeeVal=BigDecimal("0E-8"), stock="Poloniex", status=OrderStatus.ACTIVE, attempt=0)
////
////        stock.Trade(RequestTimeout(1000), stock.activeKey, listOf(o1))
////    }
////
////    @Test fun TradeArb() {
////        try {
//////            launch(CommonPool) { RutBot.Start() }
//////
//////            Thread.sleep(10000)
////
////
//////            2017-05-09 15:54:48,857 INFO  RutBot.checkArbitrage [0] profit - 0.00302039968456685172(eth), relative 0.00296522, priority 1, cfg 95, bot 1
//////            2017-05-09 15:54:48,857 INFO  RutBot.checkArbitrage [Bitfinex][eth_btc] have: 0.04918750 will get: 1.02162599 type: buy rate: 0.04805 amount: 1.02367334
//////            2017-05-09 15:54:48,857 INFO  RutBot.checkArbitrage [Bitfinex][btc_usd] have: 87.22156757 will get: 0.04918750 type: buy rate: 1769.7 amount: 0.04928607
//////            2017-05-09 15:54:48,857 INFO  RutBot.checkArbitrage [Bitfinex][eth_usd] have: 1.01860559 will get: 87.22156723 type: sell rate: 85.8 amount: 1.01860559
////
////
////
//////            RutBot.updatedStock = RutBot.stocksCache[name]!!
//////            RutBot.updatedStock.stateTime = LocalDateTime.now()
//////            RutBot.updatedStock.Shutdown()
////
//////            btc=0.1364756130, etc=9.7679107620, usd=87.2215675740, eth=6.9761500110, ltc=9.8734574790
////
//////            RutBot.updatedStock.walletTotal.put("btc", BigDecimal("0.1364756130").divide(BigDecimal("0.9"),RoundingMode.HALF_EVEN))
//////            RutBot.updatedStock.walletTotal.put("usd", BigDecimal("87.2215675740").divide(BigDecimal("0.9"),RoundingMode.HALF_EVEN))
//////            RutBot.updatedStock.walletTotal.put("eth", BigDecimal("6.9761500110").divide(BigDecimal("0.9"),RoundingMode.HALF_EVEN))
////            val name = "sdff"
////
////            val op = listOf(
////                    Operation("eth_btc", BigDecimal("0.04805"), 0, name, Book.asks, BigDecimal.ZERO, false)
////                            .apply { amount = BigDecimal("1.02367334"); needCur = "btc"; have = BigDecimal("0.04918750"); willGet = BigDecimal("1.02162599") },
////                    Operation("btc_usd", BigDecimal("1769.7"), 0, name, Book.asks, BigDecimal.ZERO, false)
////                            .apply { amount = BigDecimal("0.04928607"); needCur = "usd"; have = BigDecimal("87.22156757"); willGet = BigDecimal("0.04918750") },
////                    Operation("eth_usd", BigDecimal("85.8"), 0, name, Book.bids, BigDecimal.ZERO, false)
////                            .apply { amount = BigDecimal("1.01860559"); needCur = "eth"; have = BigDecimal("1.01860559"); willGet = BigDecimal("87.22156723") }
////            )
////
////            val status = if (op.any { it.real }) "ACTIVE" else "COMPLETED"
////
////            val (list, arbs)= RutBot.summarize(listOf(Arb(3, 0, 97, 0, BigDecimal("0.001"), op)))!!
//////
//////            RutBot.stateLock.write {
//////                RutBot.updatedStock.PutOrders(list[name]!!)
//////            }
////            Thread.sleep(100000000000)
////        } catch(e: Exception) {
////            RutBot.updatedStock.log.error(e.message, e)
////        }
////    }
////}