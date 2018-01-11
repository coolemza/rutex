package db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

fun initDb() {
    Database.connect("jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", driver = "org.h2.Driver")

    transaction {
        create(Currencies, Pairs, Stocks, Stock_Pair, Api_Keys)
    }

    val wexId = initStock("WEX")
    initKey(wexId, "3HFBB6QZ-1SZDU4UL-U31EM2WB-YEOR1R2P-32GQN5X7", "c1df72d8acbbd91a09a8f9f13d477b7746b623b6cdaba7101d880963a38a7549", KeyType.WALLET)
    initKey(wexId, "5AR7C4K3-VCLLC71H-QFDGR1ER-61O6I2N9-7UK88DTL", "bfcaac7e57a11441fd414c054acf80d786acd04e978a185e01b05617928cf472", KeyType.TRADE)
    initKey(wexId, "FQRT4SYK-1L6KSTOT-MBTSQ4VY-M5OUUNQR-QPBD1CS5", "d748ebb8f93d3b15634633fd6cc11b43d5904173cde058985e64938e617ed8a8", KeyType.TRADE)
    initKey(wexId, "R5VDWAQL-A7XH3MQM-LMCK8AUZ-5M0ET3TO-J93JVM20", "57b42858a0f424d32c530c7782d53c3a854363d601c7f2a00ff100b8b30ab375", KeyType.TRADE)
    initKey(wexId, "EIJU3VBY-UJ6G0Y6C-G64GE1I1-FQTOE83I-RD72415T", "0d3dd50f0ae8fe7421bb44cf75f12287bb77f615e848c563a1b5c33a484cfc62", KeyType.HISTORY)
    initKey(wexId, "HJ0NLZF3-AGHB7T0Z-HJ9ZCO83-KB1CJ8YZ-NZVTVMQ6", "0d601ed39dfae1fa52dfbb2b03de0198487f66ba6e0d7692b824d906f995624a", KeyType.DEBUG)
    initKey(wexId, "VBXDPA22-NS5W3FAO-IFKAR8QC-SUT678LM-YT6UASLB", "1e44fab9661d2e7bf696e9eab841623195715ac8566924fad9054f17196afb60", KeyType.WITHDRAW)

    transaction {
        val btcUsdId = Pairs.insert {
            it[type] = "btc_usd"
        } get Pairs.id

        val btcEId = Stocks.insert {
            it[name] = "WEX"
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

fun initKey(stockId: Int, apiKey: String, secretPart: String, keyType: KeyType) {
    transaction {
        Api_Keys.insert {
            it[stock_id] = stockId
            it[key_name] = "rutex@WEX"
            it[apikey] = apiKey
            it[secret] = secretPart
            it[nonce] = Instant.ofEpochSecond(0L).until(Instant.now(), ChronoUnit.SECONDS)
            it[type] = keyType
        }
    }
}

fun initStock(name: String): Int {
    val id = transaction {
        Stocks.insert {
            it[Stocks.name] = "WEX"
            it[history_last_id] = 0
        } get Stocks.id
    }
    return  id;
}

