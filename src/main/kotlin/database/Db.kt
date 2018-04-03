package database

import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.KodeinAware
import com.github.salomonbrys.kodein.instance
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import stock.Update
import utils.local2joda
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class Db(override val kodein: Kodein) : IDb, KodeinAware {
    val db = kodein.run {
        Database.connect(instance(Params.dbUrl), instance(Params.dbDriver), instance(Params.dbUser), instance(Params.dbPassword))
    }

    init {
        transaction {
            SchemaUtils.create(Currencies, Pairs, Stocks, Stock_Pair, Stock_Currency, Api_Keys, Wallet, Rates)
        }

        val stocks = RutData.getStocks().associateBy({ it }) { initStock(it) }

//        val currencies = RutData.getCurrencies().associateBy({ it }) {
//            initCurrency(it, it != "usd" && it != "eur" && it != "rur")
//        }

        val currencies = RutData.getCurrencies().associateBy({ it }) {
            val crypto = it != "usd" && it != "eur" && it != "rur"
            Pair(initCurrency(it, crypto), crypto)
        }

//        val pairs = RutData.getPairs().associateBy({ it }) { initPair(it) }
        val pairs = RutData.getStockPairs().map { it.value }.reduce { a, b -> a + b }.toSet().associateBy({ it }) { initPair(it) }

//        stocks.forEach { _, stockId ->
//            currencies.forEach { _, curId ->
//                initStockCurrency(stockId, curId, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "", "")
//            }
//            pairs.forEach { _, pairId ->
//                initStockPair(stockId, pairId, BigDecimal("0.002"), BigDecimal("0.001"))
//            }
//        }

        stocks.forEach { stock, stockId ->
            RutData.getStockPairs()[stock]!!.map { it.split("_") }.reduce { a, b -> a + b }.toSet().sorted().forEach {
                initStockCurrency(stockId, currencies[it]!!.first, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "", "")
            }

            RutData.getStockPairs()[stock]!!.forEach {
                initStockPair(stockId, pairs[it]!!, BigDecimal("0.002"), BigDecimal("0.001"))
            }
        }

        kodein.instance<RutKeys>(Params.testKeys).keys.forEach {
            val stockId = stocks[it.key]!!
            it.value.forEach { initKey(stockId, it.key, it.secret, it.type) }
        }
    }

    val pairs: Map<String, Int> by lazy { transaction { Pairs.selectAll().associateBy({ it[Pairs.type] }) { it[Pairs.id] } } }
    val stocks: Map<String, StockInfo> by lazy {transaction {
        Stocks.selectAll().associateBy({ it[Stocks.name] }) { StockInfo(it[Stocks.id], it[Stocks.history_last_id]) }
    } }
    val currencies: Map<String, CurrencyInfo> by lazy { transaction {
        Currencies.selectAll().associateBy({ it[Currencies.type] })
        { CurrencyInfo(it[Currencies.id], it[Currencies.type], it[Currencies.crypto]) }
    } }

    override fun getKeys(name: String): MutableList<StockKey> {
        val keys = mutableListOf<StockKey>()
        transaction {
            Api_Keys.innerJoin(Stocks).select { Stocks.name.eq(name) }.forEach {
                keys.add(StockKey(it[Api_Keys.apikey], it[Api_Keys.secret], it[Api_Keys.nonce], it[Api_Keys.type]))
            }
        }
        return keys
    }

    override fun getStockPairs(name: String) = transaction {
        Stock_Pair.innerJoin(Pairs).innerJoin(Stocks)
                .select { Stocks.name.eq(name) and Stock_Pair.enabled }
                .groupBy { it[Pairs.type] }.entries
                .associateBy({ it.key }) { PairInfo(it.value[0][Pairs.id], it.value[0][Stock_Pair.id], it.value[0][Stock_Pair.minAmount]) }
    }

    override fun getStockCurrencies(name: String): Map<String, StockCurrencyInfo> = transaction {
        Stock_Currency.innerJoin(Stocks).innerJoin(Currencies)
                .select { Stock_Currency.enabled.eq(true) and Stocks.name.eq(name) }
                .associateBy({ it[Currencies.type] }) {
                    StockCurrencyInfo(it[Stock_Currency.id])
                }
    }

    override fun saveNonce(key: StockKey) {
        transaction {
            Api_Keys.update({ Api_Keys.apikey.eq(key.key) and Api_Keys.secret.eq(key.secret) }) {
                it[Api_Keys.nonce] = key.nonce
            }
        }
    }

    override fun saveHistoryId(id: Long, stock_id: Int) {

    }


    override fun updateStockPairs(update: Map<String, PairInfo>, stockName: String) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getStockInfo(name: String) = stocks[name]!!

    override fun saveBook(stockId: Int, update: List<Update>, time: LocalDateTime, pairs: Map<String, PairInfo>) {
        transaction {
            Rates.batchInsert(update)
            {
                this[Rates.date] = local2joda(time).toDateTime()
                this[Rates.type] = it.type
                this[Rates.stock_id] = stockId
                this[Rates.pair_id] = pairs[it.pair]!!.pairId
                this[Rates.rate] = it.rate
                this[Rates.amount] = it.amount
            }
        }
    }

    override fun saveWallets(data: Map<WalletType, Map<String, BigDecimal>>, stockId: Int, time: LocalDateTime) {
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
                this[Wallet.date] = local2joda(time).toDateTime()
                this[Wallet.type] = it.type
                this[Wallet.currency_id] = it.cur_id
                this[Wallet.stock_id] = stockId
                this[Wallet.amount] = it.amount
            }
        }
    }

    override fun initKey(stockId: Int, apiKey: String, secretPart: String, keyType: KeyType) {
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

    fun initStock(name: String) = transaction {
        Stocks.insert {
            it[Stocks.name] = name
            it[history_last_id] = 0
        } get Stocks.id
    }

    fun initPair(name: String) = transaction {
        Pairs.insert {
            it[type] = name
        } get Pairs.id
    }

    override fun initStockPair(stockId: Int, pairId: Int, percent: BigDecimal, minAmount: BigDecimal) {
        transaction {
            Stock_Pair.insertIgnore {
                it[stock_id] = stockId
                it[pair_id] = pairId
                it[enabled] = true
                it[Stock_Pair.percent] = percent
                it[Stock_Pair.minAmount] = minAmount
            }
        }
    }

    fun initCurrency(name: String, crypto: Boolean) = transaction {
        Currencies.insert {
            it[type] = name
            it[Currencies.crypto] = crypto
        } get Currencies.id
    }

    fun initStockCurrency(stockId: Int, curId: Int, withdrawMin: BigDecimal, withdrawPercent: BigDecimal,
                          depositMin: BigDecimal, depositPercent: BigDecimal, address: String, tag: String) = transaction {
        Stock_Currency.insert {
            it[stock_id] = stockId
            it[currency_id] = curId
            it[enabled] = true
            it[withdraw_min] = withdrawMin
            it[withdraw_percent] = withdrawPercent
            it[deposit_min] = depositMin
            it[deposit_percent] = depositPercent
            it[Stock_Currency.address] = address
            it[Stock_Currency.tag] = tag
        } get Stock_Currency.id
    }
}
