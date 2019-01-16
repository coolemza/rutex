import database.OperationType
import kotlinx.serialization.ImplicitReflectionSerializer
import org.junit.Assume
import org.junit.jupiter.api.Test

class BitfinexTest: StockTest("Bitfinex", kodein) {

    @Test
    fun currencyInfo() = testCurrencyInfo()

    @Test
    fun pairInfo() = testPairInfo()

    @Test
    fun wallet() {
        Assume.assumeNotNull(stock.infoKey)

        testWallet("ltc")
    }

    @Test
    fun depth() = testDepth("ltc_usd")

    @Test
    fun ordersPutCancel() = testOrderLiveCycle("ltc_usd", OperationType.buy)
}

