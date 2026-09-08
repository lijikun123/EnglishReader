package com.jikunli.englishreader.sync

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.util.UUID

fun Route.webAiRoutes(
    config: AppConfig,
    database: KreaderDatabase,
    aiService: WebAiService,
    requestGuard: AiRequestGuard,
) {
    route("/v1/ai") {
        get("/status") {
            call.respond(AiStatusResponse(aiService.configured, aiService.model, "$WEB_AI_CACHE_VERSION:${aiService.model}"))
        }
        post("/translate") {
            val request = call.validAiParagraph(config, database, requestGuard)
            call.respond(AiTranslationResponse(aiService.translate(request.text)))
        }
        post("/phrases") {
            val request = call.validAiParagraph(config, database, requestGuard)
            call.respond(AiPhrasesResponse(aiService.phrases(request.text)))
        }
    }
}

private suspend fun ApplicationCall.validAiParagraph(
    config: AppConfig,
    database: KreaderDatabase,
    requestGuard: AiRequestGuard,
): AiParagraphRequest {
    requireDeclaredBodyAtMost(config.maxJsonBytes)
    val userId = aiUserId()
    requestGuard.check(userId.toString())
    val request = receive<AiParagraphRequest>()
    val bookId = try { UUID.fromString(request.bookId) }
    catch (_: IllegalArgumentException) {
        throw ApiException(HttpStatusCode.BadRequest, "book_id_invalid", "Invalid book ID")
    }
    if (!request.contentSha256.matches(Regex("[a-fA-F0-9]{64}"))) {
        throw ApiException(HttpStatusCode.BadRequest, "content_hash_invalid", "Invalid content SHA-256")
    }
    if (request.chapterIndex < 0 || request.paragraphIndex < 0) {
        throw ApiException(HttpStatusCode.BadRequest, "paragraph_invalid", "Invalid chapter or paragraph index")
    }
    val text = request.text.trim()
    if (text.isEmpty() || text.length > config.maxAiInputChars) {
        throw ApiException(
            HttpStatusCode.BadRequest,
            "ai_input_invalid",
            "AI paragraph must contain 1 to ${config.maxAiInputChars} characters",
        )
    }
    database.assertActiveBookContent(userId, bookId, request.contentSha256.lowercase())
    return request.copy(text = text, contentSha256 = request.contentSha256.lowercase())
}

private fun ApplicationCall.aiUserId(): UUID {
    val subject = principal<JWTPrincipal>()?.payload?.subject
        ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_token", "Authentication token is invalid")
    return try { UUID.fromString(subject) }
    catch (_: IllegalArgumentException) {
        throw ApiException(HttpStatusCode.Unauthorized, "invalid_token", "Authentication token is invalid")
    }
}
