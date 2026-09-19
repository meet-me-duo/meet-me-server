package com.meetme.server.shared.application.port.output

import java.util.UUID

fun interface IdGenerator {
    fun next(): UUID
}
