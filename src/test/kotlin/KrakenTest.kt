import data.Order
import database.TransferStatus
import org.junit.Test
import stock.Kraken
import stock.Transfer
import java.math.BigDecimal

class KrakenTest: RestStockTest(Kraken(RutEx.kodein)) {

    @Test fun info() = testInfo()

    @Test fun wallet() = testWallet()

    @Test fun depth() = testDepth()

    @Test fun ordersPutCancel() = testOrderLiveCycle(Order("Kraken", "sell", "ltc_usd", BigDecimal("270"), BigDecimal("0.1")))

    @Test fun deposit() = testDeposit(0, Transfer(Pair("LSyq6MBrvPNi9DDVXF81dpi7F2FSWn7M86",""), BigDecimal("0.19900000"),
            "ltc", "WEX", "Kraken", TransferStatus.PENDING, tId = "53d8c3a6f42be87958f0ae05fd4790acebb65ca7a121f2585ef5e32aa5a98168"))
}
