import api.IRut
import state.LocalState
import data.ControlMsg
//import com.github.salomonbrys.kodein.Kodein
//import com.github.salomonbrys.kodein.bind
//import com.github.salomonbrys.kodein.singleton
//import com.github.salomonbrys.kodein.with
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KLoggable
import org.kodein.di.Kodein
import api.IStock
import data.GetRates
import kotlin.reflect.full.primaryConstructor

class RutEx(val kodein: Kodein): IRut, KLoggable {
    override val logger = logger()

    override var stockList = mutableMapOf<String, IStock>()

    lateinit var localState: LocalState

    lateinit var mainHandler: Job
    val handler = CoroutineExceptionHandler { _, e -> logger.error(e.message, e) }
    override val controlChannel = Channel<ControlMsg>(capacity = Channel.UNLIMITED)

    override suspend fun getState() = GetRates().also { controlChannel.send(it) }.data.await()

    override suspend fun start(stocks: Set<String>) {
        stocks.associateTo(stockList) {
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