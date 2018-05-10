import data.Order
import database.BookType
import database.TransferStatus
import org.junit.Test
import stock.Kraken
import stock.Transfer
import java.math.BigDecimal

class KrakenTest {
    var stock = Kraken(RutEx.kodein)
    var orderId: Long = 0L

    @Test
    fun testWallet() {
        val wallet = stock.getBalance()!!
        assert(wallet.containsKey("ltc"))
    }

    @Test
    fun testDepth() {
        val depth = stock.getDepth(null, "btc_usd")
        var status = true

        depth?.pairs?.forEach {
            (it.value as Map<*, *>).forEach {
                if (!((it.key as BookType).toString().trim().contains("bids") || (it.key as BookType).toString().trim().contains("asks")))
                    status = false
            }
        }

        assert(status)
    }

    @Test
    fun testOrderLiveCycle() {
        val orders = listOf(Order("Kraken", "sell", "ltc_usd", BigDecimal("270"), BigDecimal("0.1")).apply { orderId = id })

        stock.state.activeList.addAll(orders)
        stock.putOrders(orders)
        val orderAfterCreate = stock.state.activeList.filter { it.id == orderId }.first()

        stock.cancelOrders(listOf(orderAfterCreate))
        val orderAfterCancellation = stock.state.activeList.find { it.id == orderId }

        assert(orderAfterCancellation == null)
    }

    @Test
    fun testDeposit() {
        val tl = listOf(Transfer(Pair("LSyq6MBrvPNi9DDVXF81dpi7F2FSWn7M86",""), BigDecimal("0.19900000"), "ltc", "WEX",
                "Kraken", TransferStatus.PENDING, tId = "53d8c3a6f42be87958f0ae05fd4790acebb65ca7a121f2585ef5e32aa5a98168"))
        val tu = stock.deposit(0, tl)
        assert(tu.second.first().status == TransferStatus.SUCCESS)
    }
}
