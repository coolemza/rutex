package web

import api.IRut
import data.GetWallet
import io.ktor.application.call
import io.ktor.html.respondHtml
import io.ktor.routing.Route
import io.ktor.routing.get
import kodein
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import org.kodein.di.direct
import org.kodein.di.generic.instance
import utils.getLastRates

fun Route.wallets() {
    get("wallets") {
        val wallets = kodein.direct.instance<IRut>().let { rut ->
            rut.stockList.map {
                it.key to runBlocking { GetWallet(it.key).also { rut.controlChannel.send(it) }.wallet.await() }
            }.toMap()
        }
        call.respondHtml {
            head { links() }
            body {
                MenuBar(this::class.simpleName)
                div("row") {
                    div("container col-md-1") {
                        table("table table-condensed table-striped table-bordered") {
                            thead {
                                tr { th { +"" }; wallets.keys.forEach { th { +it } } }
                            }
                            tbody {


                            }
                        }
                    }
                }
            }
        }
    }
}