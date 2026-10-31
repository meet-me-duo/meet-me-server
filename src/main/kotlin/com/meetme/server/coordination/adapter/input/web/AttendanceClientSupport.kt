package com.meetme.server.coordination.adapter.input.web

/** Display semantics only; participant/HOST authorization remains in the use case. */
internal object AttendanceClientSupport {
    fun supported(value: String?): Boolean = value == "conditional-v1"
}
