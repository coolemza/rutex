package db

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.joda.time.DateTime
import stock.Update
import java.math.BigDecimal

data class StockKey(val key: String, val secret: String, var nonce: Long, val type: KeyType)
data class PairInfo(val pairId: Int, val stockPairId: Int, var minAmount: BigDecimal)
data class CurrencyInfo(val curId: Int, val withdraw_min: BigDecimal, val withdraw_percent: BigDecimal,
                        val deposit_min: BigDecimal, val deposit_percent: BigDecimal, val address: String, val tag: String)

fun getStockId(name: String) = transaction {
    Stocks.select { Stocks.name.eq(name) }.first()[Stocks.id]
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

fun getPairs(name: String) = transaction {
    Stock_Pair.innerJoin(Pairs).innerJoin(Stocks)
            .select { Stocks.name.eq(name) and Stock_Pair.enabled }
            .groupBy { it[Pairs.type] }.entries
            .associateBy({ it.key }) { PairInfo(it.value[0][Pairs.id], it.value[0][Stock_Pair.id], it.value[0][Stock_Pair.minAmount]) }
}

fun getCurrencies(name: String): Map<String, CurrencyInfo> = transaction {
    Stock_Currency.innerJoin(Stocks).innerJoin(Currencies)
            .select { Stocks.name.eq(name) and Stock_Currency.enabled }
            .associateBy({ it[Currencies.type] }) {
                CurrencyInfo(it[Stock_Currency.id], it[Stock_Currency.withdraw_min], it[Stock_Currency.withdraw_percent],
                        it[Stock_Currency.deposit_min], it[Stock_Currency.withdraw_percent], it[Stock_Currency.address],
                        it[Stock_Currency.tag])
            }
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

fun saveBook(stockId: Int, update: List<Update>, time: DateTime, pairs: Map<String, PairInfo>) {
    transaction {
        Rates.batchInsert(update)
        {
            this[Rates.date] = time
            this[Rates.type] = it.type
            this[Rates.stock_id] = stockId
            this[Rates.pair_id] = pairs[it.pair]!!.pairId
            this[Rates.rate] = it.rate
            this[Rates.amount] = it.amount
        }
    }
}

fun saveWallets(data: Map<WalletType, Map<String, BigDecimal>>, stockId: Int, time: DateTime) {
    class WalletData(val cur_id: Int, val type: WalletType, val amount: BigDecimal)

    val curMap = transaction {
        Currencies.selectAll().associateBy({ it[Currencies.type] }) { it[Currencies.id] }
    }

    val list = data.entries.filter { it.value.isNotEmpty() }
            .map { wal -> wal.value.map { WalletData(curMap[it.key]!!, wal.key, it.value) } }
            .reduce { a, b -> a + b }

    transaction {
        Wallet.deleteWhere { Wallet.stock_id.eq(stockId) }
        Wallet.batchInsert(list)
        {
            this[Wallet.date] = time
            this[Wallet.type] = it.type
            this[Wallet.currency_id] = it.cur_id
            this[Wallet.stock_id] = stockId
            this[Wallet.amount] = it.amount
        }
    }
}

