import database.Db
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import stock.IStock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.reflect.full.primaryConstructor

object RutEx {
    val stateLock = ReentrantReadWriteLock()
    lateinit var stocks: Map<String, IStock>

    private var stop = false

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
        val stocks = listOf("WEX")
        this.stocks = stocks.map { it to Class.forName("stock.$it").kotlin.primaryConstructor?.call(Db()) as IStock }.toMap()

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