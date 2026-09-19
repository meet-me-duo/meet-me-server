package com.meetme.server.coordination.application.port.output

import java.time.Duration

fun interface RetryDelayPort {
    fun sleep(duration: Duration)
}

fun interface JitterPort {
    fun nextLong(upperExclusive: Long): Long
}

fun interface MonotonicTimePort {
    fun nanoTime(): Long
}
