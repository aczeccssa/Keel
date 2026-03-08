package com.keel.kernel.config

import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main() {
    testEngine("Netty", KeelEngine.Netty, 8081)
    testEngine("CIO", KeelEngine.CIO, 8082)
    testEngine("Tomcat", KeelEngine.Tomcat, 8083)
    testEngine("Jetty", KeelEngine.Jetty, 8084)

    println("All Keel kernels starting on ports 8081-8084! Press enter to exit.")
    readlnOrNull()

    exitProcess(0)
}

private fun testEngine(name: String, engine: KeelEngine, port: Int) {
    println("Testing Keel with $name Engine")
    thread {
        runKeel(port = port) {
            server {
                this.engine = engine
            }
            routing {
                get("/") {
                    call.respondText("Hello $name from Keel!")
                }
            }
        }
    }
}
