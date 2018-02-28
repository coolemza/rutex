import com.github.salomonbrys.kodein.*
import data.DepthBook
import database.Db
import database.IDb
import database.KeyType
import database.RutData
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import stock.IStock
import stock.Kraken
import stock.WEX
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.reflect.full.primaryConstructor

object RutEx {
    val stateLock = ReentrantReadWriteLock()
    lateinit var stocks: Map<String, IStock>

    private var stop = false

    private val kodein = Kodein {
        bind<IDb>() with singleton { Db() }
        constant("testKeys") with RutData.getTestKeys()
    }

    init {
        val db: IDb = kodein.instance()
        val testKeys: Map<String, List<Triple<String, String, KeyType>>> = kodein.instance("testKeys")
        testKeys.forEach {
            val stockId = db.getStockInfo(it.key).id
            it.value.forEach { db.initKey(stockId, it.first, it.second, it.third) }
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            //Runtime.getRuntime().addShutdownHook(Thread { RutEx.stop() })

            //start()

            val theWex = WEX(kodein)
            //val some = theWex.getBalance()
            //val info = theWex.info()
            //info

            val depth = theWex.getDepth(DepthBook(), "ltc_btc")
            depth

            val theKraken = Kraken(kodein)
            //val some2 = theKraken.getBalance()
            val some2 = theKraken.getDepth(null, null)
            //val some2 =theKraken.getOrderInfo()

            some2
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