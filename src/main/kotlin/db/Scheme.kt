package db

import db.Stock_Pair.autoIncrement
import db.Stock_Pair.primaryKey
import db.Wallet.references
import org.jetbrains.exposed.dao.IntIdTable
import org.jetbrains.exposed.sql.Table

enum class WalletType { AVAILABLE, LOCKED, TOTAL }
enum class OrderStatus { ACTIVE, PARTIAL, COMPLETED, CANCELED, FAILED }
enum class KeyType { WALLET, TRADE, ACTIVE, DEBUG, HISTORY, WITHDRAW }

object Currencies: IntIdTable() {
    val type = varchar("type", 4)
    val crypto = bool("crypto")
}

object Pairs: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val type = varchar("type", 16)
}

object Stocks: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", 20)
    val history_last_id = long("history_last_id")

}

object Rates: Table() {
    val date = datetime("date")
    val stock_id = integer("stock_id") references Stocks.id
    val pair_id = integer("pair_id") references Pairs.id
    val type = varchar("type", 4)
    val rate = decimal("rate", 20, 8)
    val amount = decimal("amount", 20, 8)
    val depth = integer("depth")
    init { uniqueIndex(stock_id, pair_id, type, depth) }
}

object Wallet: Table() {
    val date = datetime("date")
    val stock_id = integer("stock_id") references Stocks.id
    val currency_id = reference("currency_id", Currencies)
    val type = enumeration("type", WalletType::class.java)
    val amount = decimal("amount", 20, 8)
    init { uniqueIndex(stock_id, currency_id, type) }
}

object Stock_Pair: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val pair_id = integer("pair_id") references Pairs.id
    val stock_id = integer("stock_id") references Stocks.id
    val minAmount = decimal("min_amount", 20, 8)
    val percent = decimal("percent", 20, 8)
    val enabled = bool("status") //TODO: refactor
    init { uniqueIndex(stock_id, pair_id) }
}

object Api_Keys: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val stock_id = integer("stock_id") references Stocks.id
    val key_name = varchar("key_name", 64)
    val apikey = varchar("apikey", 256)
    val secret = varchar("secret", 256)
    val nonce = long("nonce")
    val type = enumeration("type", KeyType::class.java)
}
