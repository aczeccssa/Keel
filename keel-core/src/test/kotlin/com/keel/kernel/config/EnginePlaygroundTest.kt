package com.keel.kernel.config

import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main() {
    println("Testing Keel with Netty Engine")
    thread {
        runKeel(port = 8081) {
            server {
                engine = KeelEngine.Netty
            }
            routing {
                get("/") {
                    call.respondText("Hello Netty from Keel!")
                }
            }
        }
    }

    println("Testing Keel with CIO Engine")
    thread {
        runKeel(port = 8082) {
            server {
                engine = KeelEngine.CIO
            }
            routing {
                get("/") {
                    call.respondText("Hello CIO from Keel!")
                }
            }
        }
    }

    println("Testing Keel with Tomcat Engine")
    thread {
        runKeel(port = 8083) {
            server {
                engine = KeelEngine.Tomcat
            }
            routing {
                get("/") {
                    call.respondText("Hello Tomcat from Keel!")
                }
            }
        }
    }

    println("Testing Keel with Jetty Engine")
    thread {
        runKeel(port = 8084) {
            server {
                engine = KeelEngine.Jetty
            }
            routing {
                get("/") {
                    call.respondText("Hello Jetty from Keel!")
                }
            }
        }
    }

    println("All Keel kernels starting on ports 8081-8084! Press enter to exit.")
    readlnOrNull()

    exitProcess(0)
}
