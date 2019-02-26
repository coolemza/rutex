package web

import Parameters
import com.google.gson.GsonBuilder
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.*
import io.ktor.gson.GsonConverter
import io.ktor.html.respondHtml
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.error
import kotlinx.html.body
import kotlinx.html.head
import org.kodein.di.Kodein
import org.kodein.di.KodeinAware
import org.kodein.di.generic.instance

val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

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
        get("/") {
            call.respondHtml {
                head { links() }
                body { MenuBar(this::class.simpleName) }
            }
        }
        rates()
        wallets()
    }
}

class RutexWeb(override val kodein: Kodein) : KodeinAware {
    val port: Int by instance(Parameters.port)

    fun start(wait: Boolean = false) {
        embeddedServer(factory = Netty, port = port, module = Application::module).start(wait)
    }
}
