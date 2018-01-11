import db.initDb
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.jetbrains.exposed.sql.Database
import stock.IStock
import stock.State
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.reflect.full.primaryConstructor

object RutEx {
    val stateLock = ReentrantReadWriteLock()
    lateinit var stockList: Map<String, IStock>

    private var stop = false

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            initDb()

            Runtime.getRuntime().addShutdownHook(Thread { RutEx.stop() })

            start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun start() {
        val stocks = listOf("WEX")
        stockList = stocks.map { it to Class.forName("stock.$it").kotlin.primaryConstructor?.call(State(it)) as IStock }.toMap()

        stockList.forEach { it.value.start() }

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