import data.Order
import org.amshove.kluent.shouldHaveKey
import org.junit.Test
import stock.Kraken
import java.math.BigDecimal

class KrakenTest {
    var stock = Kraken(RutEx.kodein)


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
        println(depth)
    }

    @Test
    fun testOrderInfo() {
        val theOrder = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(250), BigDecimal.valueOf(0.1))
                .apply{transactionId = "OBE3Y2-UQDBK-HLHYLH"}

        stock.getOrderInfo(theOrder, false)

        println(theOrder)
    }

    @Test
    fun testPutOrder() {
        val theOrder = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(270), BigDecimal.valueOf(0.1))
        var listOrders = listOf<Order>(theOrder)

        stock.putOrders(listOrders)

        println(theOrder)
    }

    //TO-DO: Введенно общее поле, transaction_id - обсудить.
    @Test
    fun testCancelOrder() {
        val theOrder = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(240), BigDecimal.valueOf(0.1))
                .apply { transactionId = "OOMEXB-7W5U6-K4XRYQ"}

        val theOrder2 = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(240), BigDecimal.valueOf(0.1))
                .apply { transactionId = "OMC7TZ-74J67-53WYPN"}

        val orderListForCancel = listOf<Order>(theOrder, theOrder2)

        stock.cancelOrders(orderListForCancel)

        println(theOrder)
    }


    //--------------- ФАЗА: 2 -----------------//
    @Test
    fun testWithdraw() {
        //stock.withdraw(Pair("asdf", "asdf"), "asdf", BigDecimal.valueOf(240))

    }

    @Test
    fun testHistory() {

    }
}