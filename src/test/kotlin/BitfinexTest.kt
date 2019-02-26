import api.stocks.Bitfinex
import database.OperationType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ImplicitReflectionSerializer
import org.junit.Assume
import org.junit.jupiter.api.Test

class BitfinexTest: StockTest(Bitfinex(kodein), kodein) {

    @Test
    fun currencyInfo() = testCurrencyInfo()

    @Test
    fun pairInfo() = testPairInfo()

    @Test
    fun wallet() = runBlocking{
        Assume.assumeNotNull(stock.infoKey)
        (stock as Bitfinex).controlConnector.start()
        testWallet("ltc")
    }

    @Test
    fun depth() = runBlocking {
        (stock as Bitfinex).bookConnector.start()
        testDepth("ltc_usd")
    }

    @Test
    fun ordersPutCancel() = runBlocking {
        (stock as Bitfinex).controlConnector.start()
        (stock as Bitfinex).bookConnector.start()
        testOrderLiveCycle("ltc_usd", OperationType.buy)
    }
}

