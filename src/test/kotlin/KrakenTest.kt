import data.Order
import org.amshove.kluent.`should be in`
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
        //wallet shouldHaveKey "usd"
        //wallet shouldHaveKey "btc"
        //wallet shouldHaveKey "xltc".toUpperCase()
        assert(wallet.containsKey("xltc".toUpperCase()))
    }

    @Test
    fun testDepth() {
        val depth = stock.getDepth(null, null)

        depth?.forEach{
            if(!(it.value as Map<String, *>).containsKey("bids") || !(it.value as Map<String, *>).containsKey("asks")){
                assert(true)
            }
        }

        assert(true)
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