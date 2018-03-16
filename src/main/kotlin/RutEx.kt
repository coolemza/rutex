import com.github.salomonbrys.kodein.*
import database.*
import data.DepthBook
import data.Order
import database.Db
import database.IDb
import database.KeyType
import java.math.BigDecimal
import database.RutData
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.serialization.json.JSON
import stock.IStock
import java.io.File
import stock.Kraken
import stock.WEX
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.reflect.full.primaryConstructor

enum class Params { dbUrl, dbDriver, dbUser, dbPassword, testKeys }

object RutEx {
    val stateLock = ReentrantReadWriteLock()
    lateinit var stocks: Map<String, IStock>

    private var stop = false

    val kodein = Kodein {
        constant(Params.dbUrl) with "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        constant(Params.dbDriver) with "org.h2.Driver"
        constant(Params.dbUser) with ""
        constant(Params.dbPassword) with ""
        constant(Params.testKeys) with (File("Rutex.keys").takeIf { it.exists() }?.readText()?.let { JSON.unquoted.parse<RutKeys>(it) } ?: RutData.getTestKeys())

        bind<IDb>() with singleton { Db(kodein) }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            //Runtime.getRuntime().addShutdownHook(Thread { RutEx.stop() })

            //start()

            //Order(stock: String, type: String, pair: String, rate: BigDecimal, amount: BigDecimal)
            Order("Kraken", "sell","ltcusd", BigDecimal.valueOf(240), BigDecimal.valueOf(0.1))

            //val theWex = WEX(kodein)
            //val some = theWex.getBalance()
            //val info = theWex.info()
            //info

            //val depth = theWex.getDepth(DepthBook(), "ltc_btc")
            //depth

            val theKraken = Kraken(kodein)
            //val some2 = theKraken.getBalance()
                    //val some2 = theKraken.getDepth(null, null)
            //val some2 =theKraken.getOrderInfo()
            val theOrder = Order("ORDQB6-NBKAI-4WY3ZW", "sell","ltcusd", BigDecimal.valueOf(250), BigDecimal.valueOf(0.1))

            theKraken.getOrderInfo(theOrder, false)

            println(theOrder)


           //some2
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun start() {
        this.stocks = RutData.getStocks().map {
            it to Class.forName("stock.$it").kotlin.primaryConstructor?.call(kodein) as IStock
        }.toMap()

        this.stocks.forEach { it.value.start() }

        runBlocking {
            while (!stop) {
                delay(2000)
            }
        }
    }

    fun stop() {
        stop = true
    }
}