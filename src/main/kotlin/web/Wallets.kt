package web

import api.IState
import data.GetWallet
import io.ktor.application.call
import io.ktor.html.respondHtml
import io.ktor.routing.Route
import io.ktor.routing.get
import kodein
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import org.kodein.di.generic.instance

fun Route.wallets() {
    get("wallets") {
        val state: IState by kodein.instance()
        val wallets = state.stockList.map { (stockName, _) ->
            stockName to runBlocking { GetWallet(stockName).also { state.controlChannel.send(it) }.wallet.await() }
        }.toMap()

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