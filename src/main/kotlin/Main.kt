import api.IRut
import bot.IWebSocket
import bot.OKWebSocket
import bot.RutHttp
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import database.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.apache.Apache
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JSON
import org.kodein.di.Kodein
import org.kodein.di.generic.*
import utils.IHttp
import web.RutexWeb
import java.io.File
import java.io.FileInputStream
import java.util.*
import javax.sql.DataSource

enum class Parameters { port, testKeys }

val hikariCfg = Properties().apply { load(FileInputStream("hikari.properties")) }
val kodein = Kodein {
    bind<DataSource>() with singleton { HikariDataSource(HikariConfig(hikariCfg)) }
    constant(Parameters.testKeys) with (File("keys.json").takeIf { it.exists() }?.readText()
        ?.let { JSON.unquoted.parse(RutKeys.serializer(), it) } ?: RutData.getTestKeys())
    constant(Parameters.port) with 9009

    bind<IDb>() with singleton { Db(kodein) }
    bind<HttpClient>() with singleton { HttpClient(Apache) }
    bind<IWebSocket>() with provider { OKWebSocket() }
    bind<IHttp>() with singleton { RutHttp(kodein) }

    bind<IRut>() with singleton { RutEx(kodein) }
}

suspend fun main(args: Array<String>) {
    val rut: IRut by kodein.instance()

    Runtime.getRuntime().addShutdownHook(Thread { runBlocking { rut.stop() } })
    RutexWeb(kodein).start()
    rut.start(RutData.getStocks().keys)
}