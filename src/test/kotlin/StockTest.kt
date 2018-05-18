import data.Order
import database.BookType
import database.TransferStatus
import kotlinx.coroutines.experimental.runBlocking
import org.junit.Test
import stock.IStock
import java.math.BigDecimal
import stock.Transfer

open class StockTest(val stock: IStock) {

    fun testWallet() {
        val wallet = stock.balance()!!
        assert(wallet.containsKey("ltc"))
    }

    fun testDepth() {
        val depth = stock.depth(null, "btc_usd")
        var status = true

        depth?.pairs?.forEach {
            (it.value as Map<*, *>).forEach {
                if (!((it.key as BookType).toString().trim().contains("bids") || (it.key as BookType).toString().trim().contains("asks")))
                    status = false
            }
        }

        assert(status)
    }

    fun testOrderLiveCycle(order: Order) = runBlocking {
        stock.state.activeList.add(order)
        stock.putOrders(listOf(order))?.forEach { it.join() }
        val orderAfterCreate = stock.state.activeList.filter { it.id == order.id }.first()

        stock.cancelOrders(listOf(orderAfterCreate))?.forEach { it.join() }
        val orderAfterCancellation = stock.state.activeList.find { it.id == order.id }

        assert(orderAfterCancellation == null)
    }

    fun testDeposit(lastId: Long, transfer: Transfer) {
        val tl = listOf(transfer)
        val tu = stock.deposit(lastId, tl)
        assert(tu.second.first().status == TransferStatus.SUCCESS)
    }
}