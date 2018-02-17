package database

import stock.Update
import java.math.BigDecimal
import java.time.LocalDateTime

data class StockKey(val key: String, val secret: String, var nonce: Long, val type: KeyType)
data class PairInfo(val pairId: Int, val stockPairId: Int, var minAmount: BigDecimal)
data class StockInfo(val id: Int, val historyId: Long)
data class CurrencyInfo(val id: Int, val name: String, val crypto: Boolean)
data class StockCurrencyInfo(val curId: Int)

interface IDb {
    fun getStockInfo(name: String): StockInfo
    fun getKeys(name: String): MutableList<StockKey>
    fun getStockPairs(name: String): Map<String, PairInfo>
    fun getStockCurrencies(name: String): Map<String, StockCurrencyInfo>

    fun saveNonce(key: StockKey)
    fun saveBook(stockId: Int, update: List<Update>, time: LocalDateTime, pairs: Map<String, PairInfo>)
    fun saveWallets(data: Map<WalletType, Map<String, BigDecimal>>, stockId: Int, time: LocalDateTime)
    fun saveHistoryId(id: Long, stock_id: Int)

    fun updateStockPairs(update: Map<String, PairInfo>, stockName: String)
    fun initStockPair(stockId: Int, pairId: Int, percent: BigDecimal, minAmount: BigDecimal)
    fun initKey(stockId: Int, apiKey: String, secretPart: String, keyType: KeyType)
}