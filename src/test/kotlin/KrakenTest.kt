import api.stocks.Kraken
import database.OperationType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ImplicitReflectionSerializer
import org.junit.Assume
import org.junit.jupiter.api.Test

class KrakenTest : StockTest(Kraken.name, kodein) {

    @Test
    fun currencyInfo() = testCurrencyInfo()

    @Test
    fun info() = testPairInfo()

    @Test
    fun wallet() = runBlocking {
        Assume.assumeNotNull(stock.infoKey)
        testWallet("ltc")
    }

    @Test
    fun depth() = testDepth("ltc_usd")

    @Test
    fun ordersPutCancel() = runBlocking {
        Assume.assumeNotNull(stock.infoKey)
        testOrderLiveCycle("ltc_usd", OperationType.sell)
    }

}
