import bots.IBot
import ch.qos.logback.classic.util.ContextInitializer
//import com.github.salomonbrys.kodein.Kodein
//import com.github.salomonbrys.kodein.bind
//import com.github.salomonbrys.kodein.singleton
//import com.github.salomonbrys.kodein.with
import database.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.json.JSON
import kotlinx.serialization.parse
import mu.KLoggable
import org.kodein.di.Kodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.singleton
import org.kodein.di.generic.with
import stock.IStock
import stock.OrderUpdate
import java.io.File
import kotlin.reflect.full.primaryConstructor

enum class Parameters { dbUrl, dbDriver, dbUser, dbPassword, testKeys }

object RutEx: KLoggable {
    init { System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback.xml") }
    override val logger = logger()

    val stateLock = Mutex()
    private lateinit var stocks: Map<String, IStock>
    private val botList = mutableMapOf<Int, IBot>()

    @UseExperimental(ImplicitReflectionSerializer::class)
    val kodein = Kodein {
        constant(Parameters.dbUrl) with "jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
        constant(Parameters.dbDriver) with "org.h2.Driver"
        constant(Parameters.dbUser) with ""
        constant(Parameters.dbPassword) with ""
        constant(Parameters.testKeys) with (File("Rutex.keys").takeIf { it.exists() }?.readText()?.let { JSON.unquoted.parse<RutKeys>(it) } ?: RutData.getTestKeys())

        bind<IDb>() with singleton { Db(kodein) }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            Runtime.getRuntime().addShutdownHook(Thread { RutEx.stop() })

            logger.info { "start ${logger.name}" }

            start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun botUpdate(botId: Int, update: OrderUpdate) {
        botList[botId]?.orderUpdate(update)
    }

    private fun start() = runBlocking {
        stocks = RutData.getStocks().keys.map {
            it to Class.forName("stock.$it").kotlin.primaryConstructor?.call(kodein) as IStock
        }.toMap()

        stocks.map { launch { it.value.start() } }.onEach { it.join() }
    }

    private fun stop() = runBlocking {
        logger.info("stopping")
        stocks.map { launch { it.value.stop() } }.onEach { it.join() }
        logger.info("stopped")
    }
}