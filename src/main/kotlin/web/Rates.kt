package web

import api.IState
import data.GetRates
import io.ktor.application.call
import io.ktor.html.respondHtml
import io.ktor.routing.Route
import io.ktor.routing.get
import kodein
import kotlinx.html.*
import org.kodein.di.generic.instance
import utils.getLastRates

fun Route.rates() {
    get("rates") {
        val state: IState by kodein.instance()
        val rates = getLastRates(GetRates().also { state.controlChannel.send(it) }.data.await())
        val stocks = rates.entries.map { it.value.keys.first() }.toSet()

        call.respondHtml {
            head { links() }
            body {
                MenuBar(this::class.simpleName)
                div("row") {
                    div("container col-md-1") {
                        table("table table-condensed table-striped table-bordered") {
                            thead {
                                tr { th { rowSpan = "2"; +"" }; stocks.forEach { th { colSpan = "2"; +it } } }
                                tr { repeat(stocks.size) { th { +"ask" }; th { +"bid" }; } }
                            }
                            tbody {
                                rates.toSortedMap().forEach { pair ->
                                    tr {
                                        th { +pair.key }
                                        stocks.forEach {
                                            td { +(pair.value?.get(it)?.get("asks")?.toPlainString() ?: "") }
                                            td { +(pair.value?.get(it)?.get("bids")?.toPlainString() ?: "") }
                                        }
                                    }
                                }
                            }

                        }
                    }
                }
            }
        }
    }
}

