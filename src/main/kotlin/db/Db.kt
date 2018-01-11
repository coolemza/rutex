package db

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.security.Key

data class StockKey(val key: String, val secret: String, var nonce: Long, val type: KeyType)
data class PairInfo(val pairId: Int, val stockPairId: Int, var minAmount: BigDecimal)

fun getStockId(name: String): Int {
    return 0
}

fun getKeys(name: String): MutableList<StockKey> {
    val keys = mutableListOf<StockKey>()
    transaction {
        Api_Keys.innerJoin(Stocks).select { Stocks.name.eq(name) }.forEach {
            keys.add(StockKey(it[Api_Keys.apikey], it[Api_Keys.secret], it[Api_Keys.nonce], it[Api_Keys.type]))
        }
    }
    return keys
}

fun getPairs(name: String): Map<String, PairInfo> {
    return mapOf()
}

fun getGurrncies(name: String): Map<String, Pair<String, String>> {
    return mapOf()
}

fun saveNonce(key: StockKey) {

}

fun getLastHistoryId(): Long {
    return 0
}

fun saveHistoryId(id: Long, stock_id: Int) {

}

fun updatePairs(update: Map<String, PairInfo>, stockName: String) {

}