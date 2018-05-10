import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.util.ContextInitializer
import ch.qos.logback.core.util.StatusPrinter
import com.github.salomonbrys.kodein.Kodein
import com.github.salomonbrys.kodein.bind
import com.github.salomonbrys.kodein.singleton
import com.github.salomonbrys.kodein.with
import database.Db
import database.IDb
import database.RutData
import database.RutKeys
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.runBlocking
import kotlinx.coroutines.experimental.sync.Mutex
import kotlinx.serialization.json.JSON
import mu.KLoggable
import org.slf4j.LoggerFactory
import stock.IStock
import java.io.File
import kotlin.reflect.full.primaryConstructor
import java.net.URLClassLoader

enum class Params { dbUrl, dbDriver, dbUser, dbPassword, testKeys }

object RutEx: KLoggable {
    init { System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback.xml") }
    override val logger = logger()

    val stateLock = Mutex()
    lateinit var stocks: Map<String, IStock>

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

            logger.info { "start ${logger.name}" }

            start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun start() = runBlocking {
        stocks = RutData.getStocks().map {
            it to Class.forName("stock.$it").kotlin.primaryConstructor?.call(kodein) as IStock
        }.toMap()

        stocks.map { launch { it.value.start() } }.onEach { it.join() }
    }

    fun stop() = runBlocking {
        logger.info("stopping")
        stocks.forEach { it.value.stop()  }
        logger.info("stopped")
    }
}