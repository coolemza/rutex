import stock.Stock

open class StockTest(val stock: Stock) {

    fun testInfo() {
        val info = stock.info()!!
        assert(info.containsKey("ltc_btc"))
    }
}