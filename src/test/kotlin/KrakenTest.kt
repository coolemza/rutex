import api.stocks.Kraken
import database.OperationType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ImplicitReflectionSerializer
import org.junit.Assume
import org.junit.jupiter.api.Test

class KrakenTest : StockTest(Kraken(kodein), kodein) {

    @Test
    fun currencyInfo() = testCurrencyInfo()

    @Test
    fun info() = testPairInfo()

    @Test
    fun wallet() = runBlocking {
        Assume.assumeNotNull(stock.infoKey)
        stock.syncWallet()
        testWallet("ltc")
    }

    @Test
    fun depth() = runBlocking {
        (stock as Kraken).bookConnector.start()
        testDepth("ltc_usd")
    }

    @Test
    fun ordersPutCancel() = runBlocking {
        Assume.assumeNotNull(stock.infoKey)
        (stock as Kraken).bookConnector.start()
        testOrderLiveCycle("ltc_usd", OperationType.sell)
    }

}
