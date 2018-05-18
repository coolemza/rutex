import data.Order
import database.TransferStatus
import org.junit.Test
import stock.WEX
import java.math.BigDecimal
import stock.Transfer

class WEXTest: StockTest(WEX(RutEx.kodein)) {

    @Test fun wallet() = testWallet()

    @Test fun depth() = testDepth()

    @Test fun ordersPutCancel() = testOrderLiveCycle(Order("Kraken", "sell", "ltc_usd", BigDecimal("270"), BigDecimal("0.01")))

    @Test fun deposit() {
        testDeposit(649478158, Transfer(Pair("", ""), BigDecimal("0.01"), "ltc", "WEX", "WEX", TransferStatus.PENDING,
                tId = "53d8c3a6f42be87958f0ae05fd4790acebb65ca7a121f2585ef5e32aa5a98168", fee = BigDecimal.ZERO))
    }
}