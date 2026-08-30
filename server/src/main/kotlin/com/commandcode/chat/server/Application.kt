package com.commandcode.chat.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.path
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import java.time.Clock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import io.ktor.utils.io.cancel

fun interface QuotaGateway {
    suspend fun fetch(apiKey: String): QuotaResponse
}

@Suppress("UNUSED_PARAMETER")
fun Application.commandCodeChatModule(
    catalogue: ModelCatalogueResponse,
    quotaGateway: QuotaGateway,
    clock: Clock = Clock.systemUTC(),
) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = false })
    }
    install(StatusPages) {
        exception<QuotaFailure> { call, failure ->
            call.respondQuotaFailure(failure)
        }
        exception<Throwable> { call, _ ->
            if (call.request.path() == QUOTA_PATH) {
                call.respondQuotaError(
                    HttpStatusCode.InternalServerError,
                    "internal_error",
                    "Internal server error",
                )
            } else {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("internal_error", "Internal server error"),
                )
            }
        }
    }

    val quotaPermits = Semaphore(32)

    routing {
        get("/v1/goat/models") {
            if (call.rejectUnexpectedGetBody()) return@get
            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=300")
            call.respond(catalogue)
        }

        get(QUOTA_PATH) {
            if (call.rejectUnexpectedGetBody()) return@get
            val apiKey = call.bearerToken()
            if (apiKey == null) {
                call.respondQuotaError(
                    HttpStatusCode.Unauthorized,
                    "auth_required",
                    "Bearer authorization is required",
                )
                return@get
            }

            val quota = quotaPermits.withPermit { quotaGateway.fetch(apiKey) }
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            call.respond(quota)
        }
    }
}

private const val QUOTA_PATH = "/v1/goat/quota"
private const val MAX_BEARER_TOKEN_LENGTH = 8_192
private const val MAX_GET_REQUEST_BODY_BYTES = 0L
private val BEARER_HEADER = Regex("^Bearer ([^\\s]+)$", RegexOption.IGNORE_CASE)

private suspend fun ApplicationCall.rejectUnexpectedGetBody(): Boolean {
    val body = receiveChannel()
    val hasFixedLengthBody = (request.contentLength() ?: 0L) > MAX_GET_REQUEST_BODY_BYTES
    val hasStreamingBody = request.headers.getAll(HttpHeaders.TransferEncoding).orEmpty().isNotEmpty()
    val hasUnframedBody = !body.isClosedForRead
    if (!hasFixedLengthBody && !hasStreamingBody && !hasUnframedBody) return false

    body.cancel()
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respond(
        HttpStatusCode.PayloadTooLarge,
        ErrorResponse("request_body_not_allowed", "Request bodies are not allowed"),
    )
    return true
}

private fun ApplicationCall.bearerToken(): String? {
    val values = request.headers.getAll(HttpHeaders.Authorization) ?: return null
    if (values.size != 1) return null
    val token = BEARER_HEADER.matchEntire(values.single())?.groupValues?.get(1) ?: return null
    return token.takeIf { it.isNotBlank() && it.length <= MAX_BEARER_TOKEN_LENGTH }
}

private suspend fun ApplicationCall.respondQuotaFailure(failure: QuotaFailure) {
    when (failure.category) {
        QuotaFailure.Category.UNAUTHORIZED -> respondQuotaError(
            HttpStatusCode.Unauthorized,
            "command_code_unauthorized",
            "Command Code rejected the API key",
        )
        QuotaFailure.Category.FORBIDDEN -> respondQuotaError(
            HttpStatusCode.Forbidden,
            "command_code_forbidden",
            "Command Code denied access",
        )
        QuotaFailure.Category.RATE_LIMITED -> respondQuotaError(
            HttpStatusCode.TooManyRequests,
            "command_code_rate_limited",
            "Command Code rate limit exceeded",
        )
        QuotaFailure.Category.TIMEOUT -> respondQuotaError(
            HttpStatusCode.GatewayTimeout,
            "command_code_timeout",
            "Command Code quota request timed out",
        )
        QuotaFailure.Category.REDIRECT,
        QuotaFailure.Category.OVERSIZED,
        QuotaFailure.Category.MALFORMED,
        -> respondQuotaError(
            HttpStatusCode.BadGateway,
            "command_code_bad_response",
            "Command Code returned an invalid response",
        )
        QuotaFailure.Category.UNAVAILABLE -> respondQuotaError(
            HttpStatusCode.BadGateway,
            "command_code_unavailable",
            "Command Code quota service is unavailable",
        )
    }
}

private suspend fun ApplicationCall.respondQuotaError(
    status: HttpStatusCode,
    code: String,
    message: String,
) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respond(status, ErrorResponse(code, message))
}
