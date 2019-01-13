package database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.direct
import org.kodein.di.generic.instance
import org.kodein.di.newInstance
import api.Transfer
import utils.local2joda
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

open class Db(final override val kodein: Kodein) : IDb, KodeinAware {
    init {
        kodein.direct.newInstance { Database.connect(kodein.direct.instance<DataSource>()) }
    }

    init {

        transaction {
            SchemaUtils.create(Currencies, Pairs, Stocks, Stock_Pair, Stock_Currency, Api_Keys, Wallet, Rates)
        }

//        val stocks = RutData.getStocks().associateBy({ it }) { initStock(it) }
        val stocks = RutData.getStocks().map { it.key to initStock(it.toPair()) }.toMap()

        val currencies = RutData.getCurrencies().associateBy({ it }) {
            val crypto = RutData.isCrypto(it)
            Pair(initCurrency(it, crypto), crypto)
        }

        val pairs = RutData.getStockPairs().map { it.value.keys }.reduce { a, b -> a + b }.toSet().associateBy({ it }) { initPair(it) }

        stocks.forEach { stock, stockId ->
            RutData.getStockPairs()[stock]!!.map { it.key.split("_") }.reduce { a, b -> a + b }.toSet().sorted().forEach {
                initStockCurrency(stockId, currencies[it]!!.first, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "", "")
            }

            RutData.getStockPairs()[stock]!!.forEach {
                initStockPair(stockId, pairs[it.key]!!, BigDecimal.ZERO, BigDecimal.ZERO)
            }
        }

        kodein.direct.instance<RutKeys>(Parameters.testKeys).keys.forEach {
            val stockId = stocks[it.key]!!
            it.value.forEach { initKey(stockId, it.key, it.secret, it.type) }
        }
    }

    override val pairs: Map<String, Int> by lazy { transaction { Pairs.selectAll().associateBy({ it[Pairs.type] }) { it[Pairs.id] } } }
    private val stocks: Map<String, StockInfo> by lazy {transaction {
        Stocks.selectAll().associateBy({ it[Stocks.name] }) { StockInfo(it[Stocks.id], it[Stocks.history_last_id]) }
    } }
    override val currencies: Map<String, CurrencyInfo> by lazy { transaction {
        Currencies.selectAll().associateBy({ it[Currencies.type] })
        { CurrencyInfo(it[Currencies.id], it[Currencies.type], it[Currencies.crypto]) }
    } }

    override fun getTransfer(stockName: String, status: TransferStatus) = transaction {
        val stockFrom = Stocks.alias("stockFrom")
        val stockTo = Stocks.alias("stockTo")

        Transfers.innerJoin(stockFrom, { Transfers.from_stock_id }, { stockFrom[Stocks.id] })
                .innerJoin(stockTo, { Transfers.to_stock_id }, { stockTo[Stocks.id] })
                .innerJoin(Currencies).select { Transfers.to_stock_id.eq(stocks[stockName]!!.id) }.map {
                    Transfer(Pair(it[Transfers.address], it[Transfers.tag]), it[Transfers.amount], it[Currencies.type],
                            it[stockFrom[Stocks.name]], it[stockTo[Stocks.name]], it[Transfers.status], it[Transfers.fee],
                            it[Transfers.withdraw_id], it[Transfers.tId], it[Transfers.id])
                }
    }

    override fun saveTransfer(transfer: Transfer) {
        transaction {
            Transfers.insert {
                it[date] = local2joda(LocalDateTime.now()).toDateTime()
                it[currency_id] = currencies[transfer.cur]!!.id
                it[from_stock_id] = stocks[transfer.fromStock]!!.id
                it[to_stock_id] = stocks[transfer.toStock]!!.id
                it[amount] = transfer.amount
                it[address] = transfer.address.first
                it[tag] = transfer.address.second
                it[tId] = transfer.tId
                it[withdraw_id] = transfer.withdraw_id
                it[status] = transfer.status
            }
        }
    }

    override fun getKeys(name: String) = transaction {
        Api_Keys.innerJoin(Stocks).select { Stocks.name.eq(name) }.map {
            StockKey(it[Api_Keys.apikey], it[Api_Keys.secret], it[Api_Keys.nonce], it[Api_Keys.type])
        }
    }

    override fun getStockPairs(name: String) = transaction {
        Stock_Pair.innerJoin(Pairs).innerJoin(Stocks)
            .select { Stocks.name.eq(name) and Stock_Pair.enabled }
            .groupBy { it[Pairs.type] }.entries
            .associateBy({ it.key }) { sp ->
                sp.value[0].let {
                    PairInfo(it[Pairs.id], it[Stock_Pair.id], TradeFee(minAmount = it[Stock_Pair.minAmount],
                        makerFee = it[Stock_Pair.makerFee], takerFee = it[Stock_Pair.takerFee]))
                }
            }
    }

    override fun getStockCurrencies(name: String): Map<String, StockCurrencyInfo> = transaction {
        Stock_Currency.innerJoin(Stocks).innerJoin(Currencies)
            .select { Stock_Currency.enabled.eq(true) and Stocks.name.eq(name) }
            .associateBy({ it[Currencies.type] }) {
                StockCurrencyInfo(curId = it[Stock_Currency.currency_id],
                    fee = CrossFee(withdrawFee = Fee(percent = it[Stock_Currency.withdraw_percent], min = it[Stock_Currency.withdraw_min]),
                        depositFee = Fee(percent = it[Stock_Currency.withdraw_percent], min = it[Stock_Currency.deposit_min])),
                    address = it[Stock_Currency.address],
                    tag = it[Stock_Currency.tag])
            }
    }

    override fun saveNonce(key: StockKey) {
        transaction {
            Api_Keys.update({ Api_Keys.apikey.eq(key.key) and Api_Keys.secret.eq(key.secret) }) {
                it[Api_Keys.nonce] = key.nonce
            }
        }
    }

    override fun updateStockPairs(update: Map<String, PairInfo>, stockName: String) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override fun getStockInfo(name: String) = stocks[name]!!

//    override fun saveBook(stockId: Int, time: LocalDateTime, update: List<Update>?, fullState: DepthBook?) {
//        fullState?.let {
//            data class BookData(val stockId: Int, val pairId: Int, val type: BookType, val rate: BigDecimal, val amount: BigDecimal)
//
//            val cc = it.pairs.map { (pair, p) -> p.map { (type, t) -> t.map { BookData(stockId, pairs[pair]!!, type, it.rate, it.amount) } } }
//                    .reduce { a, b -> a + b }.reduce { a, b -> a + b }
//            transaction {
//                Rates.batchInsert(cc)
//                {
//                    this[Rates.date] = local2joda(time).toDateTime()
//                    this[Rates.type] = it.type
//                    this[Rates.stock_id] = stockId
//                    this[Rates.pair_id] = it.pairId
//                    this[Rates.rate] = it.rate
//                    this[Rates.amount] = it.amount
//                    this[Rates.full] = true
//                }
//            }
//        }
//        update?.let {
//            transaction {
//                Rates.batchInsert(it)
//                {
//                    this[Rates.date] = local2joda(time).toDateTime()
//                    this[Rates.type] = it.type
//                    this[Rates.stock_id] = stockId
//                    this[Rates.pair_id] = pairs[it.pair]!!
//                    this[Rates.rate] = it.rate
//                    this[Rates.amount] = it.amount
//                }
//            }
//        }
//    }

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

    override fun updateCrossFee(update: Map<String, CrossFee>, stockName: String) = transaction {
        update.forEach { cur, fee ->
            Stock_Currency.update({ Stock_Currency.currency_id.eq(currencies[cur]!!.id) and
                    Stock_Currency.stock_id.eq(stocks[stockName]!!.id) }) {
                it[Stock_Currency.deposit_min] = fee.depositFee.min
                it[Stock_Currency.deposit_percent] = fee.depositFee.percent
                it[Stock_Currency.withdraw_min] = fee.withdrawFee.min
                it[Stock_Currency.withdraw_percent] = fee.withdrawFee.percent
            }
        }
    }

    override fun updateTradeFee(update: Map<String, TradeFee>, stockName: String) = transaction {
        update.forEach { p ->
            Stock_Pair.innerJoin(Pairs).innerJoin(Stocks)
                .update({ Pairs.type.eq(p.key) and Stocks.name.eq(stockName) }) {
                    it[Stock_Pair.minAmount] = p.value.minAmount
                    it[Stock_Pair.makerFee] = p.value.makerFee
                    it[Stock_Pair.takerFee] = p.value.takerFee
                }
        }
    }

    override fun saveHistoryId(id: Long, stock_id: Int) {
        transaction {
            Stocks.update({ Stocks.id.eq(stock_id) }) {
                it[Stocks.history_last_id] = id
            }
        }
    }

    override fun initKey(stockId: Int, apiKey: String, secretPart: String, keyType: KeyType) {
        transaction {
            Api_Keys.insert {
                it[stock_id] = stockId
                it[key_name] = "rutex"
                it[apikey] = apiKey
                it[secret] = secretPart
                it[nonce] = Instant.ofEpochSecond(0L).until(Instant.now(), ChronoUnit.SECONDS)
                it[type] = keyType
            }
        }
    }

    private fun initStock(data: Pair<String, Int>) = transaction {
        val (stock, id) = data
        Stocks.select { Stocks.name.eq(stock) }.let { s ->
            if (s.asIterable().toList().isEmpty()) {
                Stocks.insert {
                    it[Stocks.id] = id
                    it[Stocks.name] = stock
                    it[history_last_id] = 0
                }[Stocks.id]!!
            } else {
                s.first()[Stocks.id]
            }
        }
    }

    private fun initPair(name: String) = transaction {
        Pairs.select { Pairs.type.eq(name) }.let {
            if (it.asIterable().toList().isEmpty()) {
                Pairs.insert {
                    it[type] = name
                } get Pairs.id
            } else {
                it.first()[Pairs.id]
            }
        }
    }

    private fun initCurrency(name: String, crypto: Boolean) = transaction {
        Currencies.select { Currencies.type.eq(name) }.let { c ->
            if (c.asIterable().toList().isEmpty()) {
                Currencies.insert {
                    it[type] = name
                    it[Currencies.crypto] = crypto
                }[Currencies.id]!!
            } else {
                c.first()[Currencies.id]
            }
        }
    }

    private fun initStockCurrency(stockId: Int, curId: Int, withdrawMin: BigDecimal, withdrawPercent: BigDecimal,
                                  depositMin: BigDecimal, depositPercent: BigDecimal, address: String, tag: String) = transaction {
        Stock_Currency.insertIgnore {
            it[stock_id] = stockId
            it[currency_id] = curId
            it[enabled] = true
            it[withdraw_min] = withdrawMin
            it[withdraw_percent] = withdrawPercent
            it[deposit_min] = depositMin
            it[deposit_percent] = depositPercent
            it[Stock_Currency.address] = address
            it[Stock_Currency.tag] = tag
        }
    }

    private fun initStockPair(stockId: Int, pairId: Int, percent: BigDecimal, minAmount: BigDecimal) {
        transaction {
            Stock_Pair.insertIgnore {
                it[stock_id] = stockId
                it[pair_id] = pairId
                it[enabled] = true
                it[Stock_Pair.makerFee] = percent
                it[Stock_Pair.takerFee] = percent
                it[Stock_Pair.minAmount] = minAmount
            }
        }
    }
}
