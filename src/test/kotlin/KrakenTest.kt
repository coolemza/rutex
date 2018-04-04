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
       // wallet.containsKey(C.ltc)
        wallet shouldHaveKey "ltc"
    }

    @Test
    fun testDepth() {
        val depth = stock.getDepth(null, null)
        var status = true

        depth?.forEach{
            (it.value as Map<*, *>).forEach{
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
        val listOrders = mutableListOf<Order>(theOrder)

        stock.state.activeList.addAll(listOrders)
        stock.putOrders(listOrders)
        val orderAfterCreate = stock.state.activeList.filter { it.id == orderId }.first()


        listOrders.clear()
        listOrders.add(orderAfterCreate)
        stock.cancelOrders(listOrders)
        val orderAfterCancellation = stock.state.activeList.find { it.id == orderId }

        assert(orderAfterCancellation == null)
    }

    @Test
    fun testHistory() {
        assert(stock.updateHistory(2) > 0)
    }
}
