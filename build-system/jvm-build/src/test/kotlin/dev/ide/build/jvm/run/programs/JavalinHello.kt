package dev.ide.build.jvm.run.programs

import io.javalin.Javalin
import java.net.URI

/** Starts a server on a free port, asks it one question over HTTP, prints the answer and stops. */
fun main() {
    val app = Javalin.create().get("/hello/{name}") { ctx -> ctx.result("hello " + ctx.pathParam("name")) }.start(0)
    val body = URI("http://localhost:${app.port()}/hello/vm").toURL().openStream().use { String(it.readAllBytes()) }
    println("GOT $body")
    app.stop()
}
