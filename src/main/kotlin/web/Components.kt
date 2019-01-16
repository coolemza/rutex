package web

import kotlinx.html.*

fun BODY.MenuBar(active: String? = null, dateFrom: String? = null, dateTo: String? = null, full: Boolean = false) {
    nav ("navbar navbar-default") {
        div("container-fluid") {
            div("navbar-header") {
                a(classes = "navbar-brand") { +"RutEx"}
            }
            ul("nav navbar-nav") {
                listOf("Rates", "Wallets").forEach {
                    it.let { li(if (it == active) "active" else "") { a { href = it.toLowerCase(); +it } } }
                }
            }
            dateFrom?.run {
                form(classes = "form-inline my-2 my-lg-0") {
                    input(type = InputType.date, name = "dateFrom") { value = dateFrom }
                    input(type = InputType.date, name = "dateTo") { value = dateTo!! }
                    button(classes = "btn btn-outline-success my-2 my-sm-0", type = ButtonType.submit) { +"Show" }
                    input(type = InputType.checkBox, name = "full") { checked = full; value = "true"; +"full" }
                }
            }
        }
    }
}

fun HEAD.links() {
    link(rel = "stylesheet", href = "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css")
    link(rel = "stylesheet", href = "static/rutstat.css", type = "text/css")
    script(src = "https://code.jquery.com/jquery-3.1.0.min.js") {}
    script(src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js") {}
}

fun HEADER.links() {
    link(rel = "stylesheet", href = "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css")
    link(rel = "stylesheet", href = "rutstat.css", type = "text/css")
    script(src = "https://code.jquery.com/jquery-3.1.0.min.js") {}
    script(src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js") {}
}
