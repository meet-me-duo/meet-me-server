package com.meetme.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class MeetMeServerApplication

fun main(args: Array<String>) {
    runApplication<MeetMeServerApplication>(*args)
}
