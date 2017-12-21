package db

import java.math.BigDecimal

data class StockKey(val key: String, val secret: String, var nonce: Long, val type: String)
data class PairInfo(val pairId: Int, val stockPairId: Int, var minAmount: BigDecimal)

fun getStockId(name: String): Int {
    return 0
}

fun getKeys(name: String): MutableList<StockKey> {
    return mutableListOf()
}

fun getPairs(name: String): Map<String, PairInfo> {
    return mapOf()
}

fun getGurrncies(name: String): Map<String, Pair<String,String>> {
    return mapOf()
}

fun saveNonce(key: StockKey) {

}

fun getLastHistoryId(): Long {
    return 0
}

fun  saveHistoryId(id: Long, stock_id: Int) {

}

fun updatePairs(update: Map<String, PairInfo>, stockName: String) {

}