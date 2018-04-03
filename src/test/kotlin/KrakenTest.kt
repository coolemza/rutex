import data.Order
import database.BookType
import org.amshove.kluent.shouldHaveKey
import org.junit.Test
import stock.Kraken
import java.math.BigDecimal

class KrakenTest {
    var stock = Kraken(RutEx.kodein)
    var orderId: Long = 0L

    @Test
    fun testWallet() {
        val wallet = stock.getBalance()!!
        wallet shouldHaveKey "ltc"
    }

    @Test
    fun testDepth() {
        val depth = stock.getDepth(null, null)
        var status: Boolean = true

        depth?.forEach{
            (it.value as Map<String, *>).forEach{
                if(!((it.key as BookType).toString().trim().contains("bids") || (it.key as BookType).toString().trim().contains("asks")))
                    status = false
            }
        }

        assert(status)
    }

    @Test
    fun testInfo() {
        stock.info()
    }

    @Test
    fun testOrderLiveCycle() {
        val theOrder = Order("Kraken", "sell", "ltcusd", BigDecimal.valueOf(270), BigDecimal.valueOf(0.1)).apply { orderId = id }
        var listOrders = mutableListOf<Order>(theOrder)

        stock.state.activeList.addAll(listOrders)
        stock.putOrders(listOrders)
        var orderAfterCreate = stock.state.activeList.filter { it.id == orderId }.first()


        listOrders.clear()
        listOrders.add(orderAfterCreate)
        stock.cancelOrders(listOrders)
        var orderAfterCancellation = stock.state.activeList.find { it.id == orderId }

        assert(orderAfterCancellation == null)
    }

    @Test
    fun testHistory() {
        stock.updateHistory(2) is Long
    }
}