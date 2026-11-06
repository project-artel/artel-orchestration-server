package kr.artel.orchestration

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class ArtelOrchestrationApplication

fun main(args: Array<String>) {
    runApplication<ArtelOrchestrationApplication>(*args)
}
