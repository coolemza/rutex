package db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal

fun initDb() {
    Database.connect("jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", driver = "org.h2.Driver")

    transaction {
        create(Currencies, Pairs, Stocks, Stock_Pair)
    }

    transaction {
        val btcUsdId = Pairs.insert {
            it[type] = "btc_usd"
        } get Pairs.id

        val btcEId = Stocks.insert {
            it[name] = "BTCe"
            it[history_last_id] = 0
        } get Stocks.id

        Stock_Pair.insert {
            it[stock_id] = btcEId
            it[pair_id] = btcUsdId
            it[enabled] = true
            it[minAmount] = BigDecimal.ONE
            it[percent] = BigDecimal.TEN
        } get Stock_Pair.id
    }
}

