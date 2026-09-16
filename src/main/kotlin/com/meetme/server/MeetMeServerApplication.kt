package com.meetme.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class MeetMeServerApplication

fun main(args: Array<String>) {
    runApplication<MeetMeServerApplication>(*args)
}
