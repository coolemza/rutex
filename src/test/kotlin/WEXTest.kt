import org.amshove.kluent.shouldHaveKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThat
import org.junit.Test
import stock.*

class WEXTest {
    var stock = WEX(TestExpDb())

    @Test
    fun testWallet() {
        val wallet = stock.getBalance()!!
        wallet shouldHaveKey "usd"
        wallet shouldHaveKey "btc"
        wallet shouldHaveKey "ltc"
    }
}