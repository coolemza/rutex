package database

import api.Transfer
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

data class StockKey(val key: String, val secret: String, var nonce: Long, val type: KeyType, var busy: Boolean = false)
data class PairInfo(val pairId: Int, val stockPairId: Int, var fee: TradeFee)
data class StockInfo(val id: Int, val historyId: Long)
data class CurrencyInfo(val id: Int, val name: String, val crypto: Boolean)
data class StockCurrencyInfo(val curId: Int = 0, var fee: CrossFee, val address: String = "", val tag: String = "")
data class TradeFee(var minAmount: BigDecimal, val makerFee: BigDecimal, val takerFee:BigDecimal) {
    val makerFeeVal = BigDecimal.ONE - makerFee.divide(BigDecimal("100")).setScale(8, RoundingMode.DOWN)
    val takerFeeVal = BigDecimal.ONE - takerFee.divide(BigDecimal("100")).setScale(8, RoundingMode.DOWN)

    override fun equals(other: Any?): Boolean {
        return when(other) {
            is TradeFee -> {
                this.minAmount.compareTo(other.minAmount) == 0 && this.makerFee.compareTo(other.makerFee) == 0 &&
                        this.makerFee.compareTo(other.makerFee) == 0
            }
            else -> super.equals(other)
        }
    }
}
data class CrossFee(val withdrawFee: Fee = Fee(), val depositFee: Fee = Fee()) {
    override fun equals(other: Any?): Boolean {
        return when(other) {
            is CrossFee -> this.withdrawFee.equals(other.withdrawFee) && this.depositFee.equals(other.depositFee)
            else -> super.equals(other)
        }
    }
}
data class Fee(var percent: BigDecimal = BigDecimal.ZERO, var min: BigDecimal = BigDecimal.ZERO) {
    override fun equals(other: Any?): Boolean {
        return when(other) {
            is Fee -> this.percent.compareTo(other.percent) == 0 && this.min.compareTo(other.min) == 0
            else -> super.equals(other)
        }
    }
}

interface IDb {
    val pairs: Map<String, Int>
    val currencies: Map<String, CurrencyInfo>

    fun getTransfer(stockName: String, status: TransferStatus = TransferStatus.WAITING): List<Transfer>
    fun saveTransfer(transfer: Transfer)
    fun getStockInfo(name: String): StockInfo
    fun getKeys(name: String): List<StockKey>
    fun getStockPairs(name: String): Map<String, PairInfo>
    fun getStockCurrencies(name: String): Map<String, StockCurrencyInfo>

    fun saveNonce(key: StockKey)
//    fun saveBook(stockId: Int, time: LocalDateTime, update: List<Update>? = null, fullState: DepthBook? = null)
    fun saveWallets(data: Map<WalletType, Map<String, BigDecimal>>, stockId: Int, time: LocalDateTime)
    fun saveHistoryId(id: Long, stock_id: Int)

    fun updateTradeFee(update: Map<String, TradeFee>, stockName: String)
    fun updateCrossFee(update: Map<String, CrossFee>, stockName: String)

    fun updateStockPairs(update: Map<String, PairInfo>, stockName: String)
//    fun initStockPair(stockId: Int, pairId: Int, percent: BigDecimal, minAmount: BigDecimal)
    fun initKey(stockId: Int, apiKey: String, secretPart: String, keyType: KeyType)
}