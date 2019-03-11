package database

import org.jetbrains.exposed.sql.Table
import java.math.BigDecimal

enum class BookType { asks, bids }
enum class KeyType { WALLET, TRADE, ACTIVE, DEBUG, HISTORY, WITHDRAW }
enum class OrderStatus { ACTIVE, PARTIAL, COMPLETED, CANCELED, FAILED, CANCEL_FAILED }
enum class OperationType { buy, sell }
enum class PlayType { LIMIT, FULL }
enum class TransferStatus { PENDING, WAITING, SUCCESS, FAILED }
enum class WalletType { AVAILABLE, LOCKED, TOTAL }

object Api_Keys: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val stock_id = integer("stock_id") references Stocks.id
    val key_name = varchar("key_name", 64)
    val apikey = varchar("apikey", 256)
    val secret = varchar("secret", 256)
    val nonce = long("nonce")
    val type = enumeration("type", KeyType::class)
}

object Cross_Limits_Less: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val stock_id_from = integer("stock_id_from") references Stocks.id
    val stock_id_to = integer("stock_id_to") references Stocks.id
    val currency_id = integer("cur_id") references  Currencies.id
    val limit = decimal("limit", 20, 8)
    val progress = decimal("progress", 20, 8)
    val limit_full = decimal("limit_full", 20, 8)
    val progress_full = decimal("progress_full", 20, 8)
    val stop = bool("stop").default(false)

    init { uniqueIndex(stock_id_from, stock_id_to, currency_id) }
}

object Cross_Limits: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val stock_id_from = integer("stock_id_from") references Stocks.id
    val stock_id_to = integer("stock_id_to") references Stocks.id
    val currency_id = integer("cur_id") references  Currencies.id
    val limit = decimal("limit", 20, 8)
    val progress = decimal("progress", 20, 8)
    val limit_full = decimal("limit_full", 20, 8)
    val progress_full = decimal("progress_full", 20, 8)
    val stop = bool("stop").default(false)
    init { uniqueIndex(stock_id_from, stock_id_to, currency_id) }
}

object Currencies: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val type = varchar("type", 4)
    val crypto = bool("crypto")
    init { uniqueIndex(type) }
}

object Pairs: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val type = varchar("type", 16)
    init { uniqueIndex(type) }
}

object Rates: Table() {
    val date = datetime("date")
    val stock_id = integer("stock_id")
    val pair_id = integer("pair_id")
    val type = enumeration("type", BookType::class)
    val rate = decimal("rate", 20, 8)
    val amount = decimal("amount", 20, 8).nullable()
    val full = bool("full").default(false)
}

object Stocks: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val name = varchar("name", 20)
    val walletRatio = decimal("wallet_percent", 20, 8).default(BigDecimal("0.5"))
    val history_last_id = long("history_last_id")
    init { uniqueIndex(name) }
}

object Stock_Currency: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val stock_id = integer("stock_id") references Stocks.id
    val currency_id = integer("currency_id") references  Currencies.id
    val withdraw_min = decimal("withdraw_min", 20, 8)
    val withdraw_percent = decimal("withdraw_percent", 20, 8)
    val deposit_min = decimal("deposit_min", 20, 8)
    val deposit_percent = decimal("deposit_percent", 20, 8)
    val address = varchar("address", 256)
    val tag = varchar("tag", 256)
    val enabled = bool("enabled")
}

object Stock_Pair: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val pair_id = integer("pair_id") references Pairs.id
    val stock_id = integer("stock_id") references Stocks.id
    val minAmount = decimal("min_amount", 20, 8)
    val makerFee = decimal("maker_fee", 20, 8)
    val takerFee = decimal("taker_fee", 20, 8)
    val enabled = bool("status") //TODO: refactor
    init { uniqueIndex(stock_id, pair_id) }
}

object TestWallet: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val date = datetime("date")
    val stock_id = integer("stock_id") references Stocks.id
    val currency_id = integer("currency_id") references Currencies.id
    val amount = decimal("amount", 20, 8)
    init { uniqueIndex(stock_id, currency_id) }
}

object Transfers: Table() {
    val id = integer("id").autoIncrement().primaryKey()
    val date = datetime("date")
    val currency_id = integer("cur_id") references Currencies.id
    val from_stock_id = integer("from_stock_id") references Stocks.id
    val to_stock_id = integer("to_stock_id") references Stocks.id
    val amount = decimal("amount", 20, 8)
    val fee = decimal("fee", 20, 8)
    val address = varchar("address", 256)
    val tag = varchar("tag", 256)
    val tId = varchar("t_id", 256)
    val withdraw_id = varchar("withdraw_id", 256)
    var status = Wallet.enumeration("type", TransferStatus::class.java.kotlin)
}

object Wallet: Table() {
    val date = datetime("date")
    val stock_id = integer("stock_id") references Stocks.id
    val currency_id = integer("currency_id") references  Currencies.id
    val type = enumeration("type", WalletType::class)
    val amount = decimal("amount", 20, 8)
    init { uniqueIndex(stock_id, currency_id, type) }
}


