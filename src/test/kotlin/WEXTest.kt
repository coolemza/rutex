import org.amshove.kluent.shouldHaveKey
import org.junit.Test
import stock.WEX

class WEXTest {
    var stock = WEX(RutEx.kodein)

    @Test
    fun testWallet() {
        val wallet = stock.getBalance()!!
        wallet shouldHaveKey "usd"
        wallet shouldHaveKey "btc"
        wallet shouldHaveKey "ltc"
    }
}