package db

import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.Table

enum class WalletType { AVAILABLE, LOCKED, TOTAL }
enum class OrderStatus { ACTIVE, PARTIAL, COMPLETED, CANCELED, FAILED }

object currencies: IntIdTable() {
    val type = varchar("type", 4)
    val crypto = bool("crypto")
}

object stocks: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", 20)
    val history_last_id = long("history_last_id")
}

object pair: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val type = varchar("type", 16)
}

object Rates: Table() {
    val date = datetime("date")
    val stock_id = integer("stock_id") references stocks.id
    val pair_id = integer("pair_id") references pair.id
    val type = varchar("type", 4)
    val rate = decimal("rate", 20, 8)
    val amount = decimal("amount", 20, 8)
    val depth = integer("depth")
    init { uniqueIndex(stock_id, pair_id, type, depth) }
}

object Wallet: Table() {
    val date = datetime("date")
    val stock_id = integer("stock_id") references stocks.id
    val currency_id = reference("currency_id", currencies)
    val type = enumeration("type", WalletType::class.java)
    val amount = decimal("amount", 20, 8)
    init { uniqueIndex(stock_id, currency_id, type) }
}

object stock_pair: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val pair_id = integer("pair_id") references pair.id
    val stock_id = integer("stock_id") references stocks.id
    val minAmount = decimal("min_amount", 20, 8)
    val percent = decimal("percent", 20, 8)
    val enabled = bool("status") //TODO: refactor
    init { uniqueIndex(stock_id, pair_id) }
}
