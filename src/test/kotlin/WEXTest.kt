import org.junit.Test
import stock.WEX

class WEXTest {
    var stock = WEX(RutEx.kodein)

    @Test
    fun testWallet() {
        val wallet = stock.getBalance()!!
        assert(wallet.containsKey("btc"))
    }
}