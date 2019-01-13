import database.OperationType
import org.junit.jupiter.api.Test

class KrakenTest : StockTest("Kraken", RutEx.kodein) {

    @Test
    fun currencyInfo() = testCurrencyInfo()

    @Test
    fun info() = testPairInfo()

    @Test
    fun wallet() = testWallet("ltc")

    @Test
    fun depth() = testDepth("ltc_usd")

    @Test
    fun ordersPutCancel() = testOrderLiveCycle("ltc_usd", OperationType.sell)

}
