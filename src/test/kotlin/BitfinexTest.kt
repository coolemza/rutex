import database.OperationType
import org.junit.jupiter.api.Test

class BitfinexTest: StockTest("Bitfinex", RutEx.kodein) {

    @Test
    fun currencyInfo() = testCurrencyInfo()

    @Test
    fun pairInfo() = testPairInfo()

    @Test
    fun wallet() = testWallet("ltc")

    @Test
    fun depth() = testDepth("ltc_usd")

    @Test
    fun ordersPutCancel() = testOrderLiveCycle("ltc_usd", OperationType.buy)
}

