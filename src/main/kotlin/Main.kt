import api.IState
import api.IStock
import api.LocalState
import api.stocks.Bitfinex
import api.stocks.Kraken
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import database.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JSON
import org.kodein.di.Kodein
import org.kodein.di.generic.*
import utils.*
import web.RutexWeb
import java.io.File
import java.io.FileInputStream
import java.util.*
import javax.sql.DataSource

enum class Parameters {
    key,
    port,
    testKeys,

    saveState,
    debug
}

val hikariCfg = Properties().apply { load(FileInputStream("hikari.properties")) }
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
@ExperimentalUnsignedTypes
@KtorExperimentalAPI
val kodein = Kodein {
    bind<DataSource>() with singleton { HikariDataSource(HikariConfig(hikariCfg)) }
    constant(Parameters.testKeys) with (File("keys.json").takeIf { it.exists() }?.readText()
        ?.let { JSON.unquoted.parse(RutKeys.serializer(), it) } ?: RutData.getTestKeys())
    constant(Parameters.port) with 9009
    constant(Parameters.key) with ""
    constant("stocks") with RutData.getStocks().keys

    bind<IDb>() with singleton { Db(kodein) }
    bind<HttpClient>() with singleton { HttpClient(Apache) }
    bind<IHttp>() with singleton { RutHttp(kodein) }

    bind<IWebSocket>() with provider { CIOWebSocket() }
    bind<IWebSocket>(tag = "secure") with provider { OKWebSocket() }

    bind<IState>() with singleton { LocalState(kodein) }

    bind<IStock>(tag = StockId.Kraken) with singleton { Kraken(kodein) }
    bind<IStock>(tag = StockId.Bitfinex) with singleton { Bitfinex(kodein) }
}

@KtorExperimentalAPI
@ExperimentalUnsignedTypes
@ExperimentalCoroutinesApi
@ObsoleteCoroutinesApi
suspend fun main() {
    val state: IState by kodein.instance()

    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { state.stop() } })
    RutexWeb(kodein).start()
    state.start()
}