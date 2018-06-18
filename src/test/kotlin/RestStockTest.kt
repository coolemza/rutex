import data.Order
import database.BookType
import database.KeyType
import database.OrderStatus
import database.TransferStatus
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Assume.assumeNotNull
import stock.RestStock
import stock.Transfer

open class RestStockTest(val resStock: RestStock): StockTest(resStock){

    fun testWallet() {
        stock.getKeys(KeyType.WALLET).takeIf { it.isNotEmpty() }?.let {
            val wallet = stock.balance()!!
            assert(wallet.containsKey("ltc"))
        } ?: assumeNotNull(null)
    }

    fun testDepth() {
        val depth = resStock.depth(null, "btc_usd")
        var status = true

        depth?.pairs?.forEach {
            (it.value as Map<*, *>).forEach {
                if (!((it.key as BookType).toString().trim().contains("bids") || (it.key as BookType).toString().trim().contains("asks")))
                    status = false
            }
        }

        assert(status)
    }

    fun testOrderLiveCycle(order: Order) {
        listOf(stock.getKeys(KeyType.ACTIVE), stock.getKeys(KeyType.TRADE))
                .takeIf { it.all { it.isNotEmpty() } }?.let {
                    runBlocking {
                        stock.activeList.add(order)
                        stock.putOrders(listOf(order))?.forEach { it.join() }
                        val orderAfterCreate = stock.activeList.first { it.id == order.id }

                        val orderUpdate = stock.orderInfo(orderAfterCreate)
                        assert(orderUpdate!!.status == OrderStatus.ACTIVE)

                        stock.cancelOrders(listOf(orderAfterCreate))?.forEach { it.join() }
                        val orderAfterCancellation = stock.activeList.find { it.id == order.id }

                        assert(orderAfterCancellation == null)
                    }
                } ?: assumeNotNull(null)
    }

    fun testDeposit(lastId: Long, transfer: Transfer) {
        stock.getKeys(KeyType.HISTORY).takeIf { it.isNotEmpty() }?.let {
            val tl = listOf(transfer)
            val tu = stock.deposit(lastId, tl)
            assert(tu.second.first().status == TransferStatus.SUCCESS)
        } ?: assumeNotNull(null)
    }
}