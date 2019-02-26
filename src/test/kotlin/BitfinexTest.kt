import api.stocks.Bitfinex
import database.OperationType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ImplicitReflectionSerializer
import org.junit.Assume
import org.junit.jupiter.api.Test

class BitfinexTest: StockTest(Bitfinex.name, kodein) {

    @Test
    fun currencyInfo() {
        Assume.assumeNotNull(stock.infoKey)
        testCurrencyInfo()
    }

    @Test
    fun pairInfo() {
        Assume.assumeNotNull(stock.infoKey)
        testPairInfo()
    }

    @Test
    fun wallet() = runBlocking{
        Assume.assumeNotNull(stock.infoKey)
        testWallet("ltc")
    }

    @Test
    fun depth() = runBlocking {
        testDepth("ltc_usd")
    }

    @Test
    fun ordersPutCancel() = runBlocking {
        Assume.assumeNotNull(stock.infoKey)
        testOrderLiveCycle("ltc_usd", OperationType.buy)
    }
}

