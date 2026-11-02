package io.caelumgyu.shencoulib.utils

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

fun Document.selectSingleXPath(path: String) = this.selectXpath(path).firstOrNull()
fun Element.selectSingleXPath(path: String) = this.selectXpath(path).firstOrNull()

fun Document.textByLabel(vararg labels: String): String? {
    for (el in select("p,div,span,td,li")) {
        val t = el.text().trim()
        for (lb in labels) {
            val i = t.indexOf(lb)
            if (i >= 0) {
                val v = t.substring(i + lb.length).replace(Regex("^[：:\\s]+"), "").trim()
                if (v.isNotEmpty() && v.length < 200) return v
            }
        }
    }
    return null
}
