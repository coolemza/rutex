package web

import api.IRut
import com.google.gson.GsonBuilder
import data.UpdateFullWallet
import data.UpdateLimits
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.gson.GsonConverter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.error
import kodein
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JSON
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.direct
import org.kodein.di.generic.instance
import java.time.LocalDateTime

val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
val rut: IRut by kodein.instance()

@Serializable
data class GlobalDepthBook(val time: String, val data: Map<String, Map<String, Map<String, List<Map<String, String>>>>>)

fun Application.module() {
    install(CallLogging)
    install(DefaultHeaders)
    install(ConditionalHeaders)
    install(Compression)
    install(PartialContent)
    install(AutoHeadResponse)
//    install(XForwardedHeadersSupport)
    install(StatusPages) {
        exception<Throwable> { cause ->
            environment.log.error(cause)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    install(ContentNegotiation) {
        register(ContentType.Application.Json, GsonConverter(gson))
    }

    routing {
        get("/rates") {
            val state = rut.getState()
            call.respond(JSON.unquoted.stringify(GlobalDepthBook.serializer(), GlobalDepthBook(LocalDateTime.now().toString(), state)))
        }
    }
}

class RutexWeb(override val kodein: Kodein) : KodeinAware {
    val port: Int by instance(Parameters.port)

    fun start(wait: Boolean = false) {
        embeddedServer(factory = Netty, port = port, module = Application::module).start(wait)
    }
}
