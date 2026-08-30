package com.commandcode.chat.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val port = System.getenv("PORT")?.let { value ->
        requireNotNull(value.toIntOrNull()?.takeIf { it in 1..65_535 }) {
            "PORT must be an integer from 1 to 65535"
        }
    } ?: 8080
    val catalogue = GoatModelRegistry.load(
        requireNotNull(
            Thread.currentThread().contextClassLoader.getResourceAsStream("goat-models.json")
        )
    )
    val quotaGateway = CommandCodeQuotaGateway.createDefault()

    embeddedServer(Netty, port = port) {
        commandCodeChatModule(catalogue, quotaGateway)
    }.start(wait = true)
}
