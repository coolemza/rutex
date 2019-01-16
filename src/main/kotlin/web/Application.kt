package web

import io.ktor.html.Placeholder
import io.ktor.html.Template
import io.ktor.html.insert
import kotlinx.html.*

class Application : Template<HTML> {
    val caption = Placeholder<TITLE>()
    val head = Placeholder<HEAD>()

    override fun HTML.apply() {
        classes += "mdc-typography"
        head {
            meta { charset = "utf-8" }
            meta {
                name = "viewport"
                content = "width=device-width, initial-scale=1.0"
            }
            title {
                insert(caption)
            }
            insert(head)
            link("https://fonts.googleapis.com/icon?family=Material+Icons", rel = "stylesheet")
            link(rel = "stylesheet", href = "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/css/bootstrap.min.css")
            link(rel = "stylesheet", href = "static/rutstat.css", type = "text/css")
            script(src = "https://code.jquery.com/jquery-3.1.0.min.js") {}
            script(src="https://maxcdn.bootstrapcdn.com/bootstrap/3.3.7/js/bootstrap.min.js") {}
        }
        body {
            script {
                unsafe {
                    +"""
    var WebFontConfig = {
      google: { families: [ 'Roboto:400,300,500:latin' ] }
    };
    (function() {
      var wf = document.createElement('script');
      wf.src = ('https:' == document.location.protocol ? 'https' : 'http') +
      '://ajax.googleapis.com/ajax/libs/webfont/1/webfont.js';
      wf.type = 'text/javascript';
      wf.async = 'true';
      var s = document.getElementsByTagName('script')[0];
      s.parentNode.insertBefore(wf, s);
    })();
"""
                }
            }
            div { id = "root" }
            script(src = "/main.bundle.js") {}
            //                    script(src = "/main.bundle.js") {
//                    }
        }
    }
}
