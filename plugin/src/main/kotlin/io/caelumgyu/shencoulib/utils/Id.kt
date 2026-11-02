package io.caelumgyu.shencoulib.utils

import io.nightfish.lightnovelreader.api.identifier.Identifier

fun String.toId() = Identifier("shencou_lib", this)
