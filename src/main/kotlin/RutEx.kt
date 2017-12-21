import db.*
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils.create
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.reflect.full.primaryConstructor

object RutEx {
    val stateLock = ReentrantReadWriteLock()
    lateinit var stockList: Map<String, IStock>

    private var stop = false

    @JvmStatic
    fun main(args: Array<String>) {
        Database.connect("jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE", driver = "org.h2.Driver")

        transaction { create(Rates, Wallet) }

        try {
            Runtime.getRuntime().addShutdownHook(Thread { RutEx.stop() })

            start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun start() {
        val stocks = listOf("Wex")
        stockList = stocks.map { it to Class.forName("stocks.$it").kotlin.primaryConstructor?.call(State(it)) as IStock }.toMap()

        stockList.forEach { it.value.start() }

        runBlocking {
            while (!stop) {
                delay(2000)
            }
        }
    }

    fun stop() {

    }
}