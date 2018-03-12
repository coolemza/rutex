import data.Order
import org.amshove.kluent.shouldHaveKey
import org.junit.Test
import stock.Kraken
import java.math.BigDecimal

class KrakenTest {
    var stock = Kraken(RutEx.kodein)

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
        val theOrder = Order("ORDQB6-NBKAI-4WY3ZW", "sell","ltcusd", BigDecimal.valueOf(240), BigDecimal.valueOf(0.1))

        stock.getOrderInfo(theOrder, false)

        println(theOrder)
    }

    @Test
    fun testPutOrder() {
        val theOrder = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(240), BigDecimal.valueOf(0.1))

        stock.getOrderInfo(theOrder, false)

        println(theOrder)
    }

    @Test
    fun testCancelOrder() {
        val theOrder = Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(240), BigDecimal.valueOf(0.1))

        stock.getOrderInfo(theOrder, false)

        println(theOrder)
    }
}