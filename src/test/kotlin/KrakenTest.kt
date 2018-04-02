import data.Order
import org.amshove.kluent.shouldHaveKey
import org.junit.Test
import stock.Kraken
import java.math.BigDecimal

class KrakenTest {
    var stock = Kraken(RutEx.kodein)
    var firstOrderId: Long = 111
    var secondOrderId: Long = 222


    //----------- ФАЗА: 1 ------------------//
    @Test
    fun testWallet() {
        val wallet = stock.getBalance()!!
        //wallet shouldHaveKey "usd"
        //wallet shouldHaveKey "btc"
        wallet shouldHaveKey "xltc".toUpperCase()
    }

    @Test
    fun testDepth() {
        val depth = stock.getDepth(null, null)

        //depth.

        val firstBook = depth?.entries?.firstOrNull()
        val some = (firstBook as LinkedHashMap<*, *>)?.entries?.firstOrNull()
        //val some = (firstBook as LinkedHashMap<*, *>)
                //?.entries?.firstOrNull()
        //(some as Map <*, *>) shouldHaveKey "bid"
        println(depth)
    }

    @Test
    //Этот тест не нужен
    fun testOrderInfo() {
        val theOrder = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(250), BigDecimal.valueOf(0.1))
                .apply{id = firstOrderId}

        stock.getOrderInfo(theOrder, false)

        println(theOrder)
    }

    @Test
    fun testPutOrder() {
        val theOrder = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(270), BigDecimal.valueOf(0.1)).apply { id = firstOrderId}
        var listOrders = listOf<Order>(theOrder)

        stock.putOrders(listOrders)

        println(theOrder)
    }

    //TO-DO: Введенно общее поле, transaction_id - обсудить.
    @Test
    //Объеденить с putOrder
    fun testCancelOrder() {
        val theOrder = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(240), BigDecimal.valueOf(0.1))
                .apply { id = firstOrderId}

        val theOrder2 = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(240), BigDecimal.valueOf(0.1))
                //.apply { id = secondOrderId}

        val orderListForCancel = listOf<Order>(theOrder, theOrder2)

        stock.cancelOrders(orderListForCancel)

        println(theOrder)
    }


    //--------------- ФАЗА: 2 -----------------//
    @Test
    fun testWithdraw() {
        stock.withdraw(Pair("Bi", ""), "LTC", BigDecimal.valueOf(0.01))

    }

    @Test
    fun testHistory() {
        var some = stock.updateHistory(2)
        some
    }

    @Test
    fun testInfo() {
        val info = stock.info()
        info
    }
}