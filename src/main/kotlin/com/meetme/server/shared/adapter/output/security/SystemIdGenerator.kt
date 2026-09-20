package com.meetme.server.shared.adapter.output.security

import com.meetme.server.shared.application.port.output.IdGenerator
import java.util.UUID

class SystemIdGenerator : IdGenerator {
    override fun next(): UUID = UUID.randomUUID()
}
