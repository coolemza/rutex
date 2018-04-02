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

    @Test
    fun testInfo() {
        val info = stock.info()
        info
    }

    @Test
    fun testHistory() {
        val info = stock.updateHistory(0L)
        info
    }
}