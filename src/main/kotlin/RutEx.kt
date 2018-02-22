import com.github.salomonbrys.kodein.*
import database.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.serialization.json.JSON
import stock.IStock
import java.io.File
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
            Runtime.getRuntime().addShutdownHook(Thread { RutEx.stop() })

            start()
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