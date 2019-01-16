import api.IRut
import state.LocalState
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
import bot.IWebSocket
import bot.OKWebSocket
import bot.RutHttp
import data.GetRates
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import kotlinx.serialization.ImplicitReflectionSerializer
import kotlinx.serialization.parse
import org.kodein.di.generic.*
import utils.IHttp
import web.RutexWeb
import java.io.File
import java.io.FileInputStream
import java.util.*
import javax.sql.DataSource
import kotlin.collections.HashSet
import kotlin.reflect.full.primaryConstructor

class RutEx(val kodein: Kodein): IRut, KLoggable {
    override val logger = logger()

    override var stockList = mutableMapOf<String, IStock>()

    lateinit var localState: LocalState

    lateinit var mainHandler: Job
    val handler = CoroutineExceptionHandler { _, e -> logger.error(e.message, e) }
    override val controlChannel = Channel<ControlMsg>(capacity = Channel.UNLIMITED)

    override suspend fun getState() = GetRates().also { controlChannel.send(it) }.data.await()

    override suspend fun start() {
        RutData.getStocks().keys.associateTo(stockList) {
            it to Class.forName("api.stocks.$it").kotlin.primaryConstructor?.call(kodein) as IStock
        }.toMap()

        localState = LocalState(stockList, logger, kodein)
        mainHandler = mainHandler()
        stockList.map { GlobalScope.launch { it.value.start() } }.onEach { it.join() }
    }

    override suspend fun stop() {
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