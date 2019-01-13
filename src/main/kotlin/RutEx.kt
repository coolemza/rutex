import bots.IBot
import bots.LocalState
import ch.qos.logback.classic.util.ContextInitializer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import data.ControlMsg
//import com.github.salomonbrys.kodein.Kodein
//import com.github.salomonbrys.kodein.bind
//import com.github.salomonbrys.kodein.singleton
//import com.github.salomonbrys.kodein.with
import database.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JSON
import mu.KLoggable
import org.kodein.di.Kodein
import api.IStock
import api.OrderUpdate
import bot.IWebSocket
import bot.OKWebSocket
import data.GetRates
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import javafx.application.Application.launch
import org.kodein.di.generic.*
import web.RutexWeb
import java.io.File
import java.io.FileInputStream
import java.util.*
import javax.sql.DataSource
import kotlin.reflect.full.primaryConstructor

enum class Parameters { port, testKeys }

object RutEx: KLoggable {
    init { System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback.xml") }
    val hikariCfg = Properties().apply { load(FileInputStream("hikari.properties")) }

    override val logger = logger()

    val stateLock = Mutex()
    var stockList = mutableMapOf<String, IStock>()
    private val botList = mutableMapOf<Int, IBot>()

    lateinit var localState: LocalState

    lateinit var mainHandler: Job
    val handler = CoroutineExceptionHandler { _, e -> logger.error(e.message, e) }
    val controlChannel = Channel<ControlMsg>(capacity = Channel.UNLIMITED)

    val kodein = Kodein {
        bind<DataSource>() with singleton { HikariDataSource(HikariConfig(hikariCfg)) }
        constant(Parameters.testKeys) with (File("Rutex.keys").takeIf { it.exists() }?.readText()
            ?.let { JSON.unquoted.parse(RutKeys.serializer(), it) } ?: RutData.getTestKeys())
        constant(Parameters.port) with 9009

                bind<IDb>() with singleton { Db(kodein) }
        bind<HttpClient>() with singleton { HttpClient(Apache) }
        bind<IWebSocket>() with provider { OKWebSocket() }
    }

    @JvmStatic
    suspend fun main(args: Array<String>) {
        try {
            Runtime.getRuntime().addShutdownHook(Thread { runBlocking { RutEx.stop() } })

            logger.info { "start ${logger.name}" }

            RutexWeb(kodein).start(true)
            start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun botUpdate(botId: Int, update: OrderUpdate) {
        botList[botId]?.orderUpdate(update)
    }

    suspend fun getState() = GetRates().also { controlChannel.send(it) }.data.await()

    private suspend fun start() {
        RutData.getStocks().keys.associateTo(stockList) {
            it to Class.forName("api.$it").kotlin.primaryConstructor?.call(kodein) as IStock
        }.toMap()

        localState = LocalState(stockList, logger, kodein)
        mainHandler = mainHandler()
        stockList.map { GlobalScope.launch { it.value.start() } }.onEach { it.join() }
    }

    private suspend fun stop() {
        logger.info("stopping")
        stockList.map { GlobalScope.launch { it.value.stop() } }.onEach { it.join() }
        mainHandler.cancelAndJoin()
        logger.info("stopped")
    }

    fun mainHandler() = GlobalScope.launch(handler) {
        while (isActive) {
            stockList.forEach {
                var msg = controlChannel.poll()
                while (msg != null) {
                    localState.onReceive(msg)
                    msg = controlChannel.poll()
                }

                it.value.depthChannel.poll()?.let { localState.onReceive(it) }
            }
        }
    }
}