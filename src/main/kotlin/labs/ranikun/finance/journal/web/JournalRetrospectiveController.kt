package labs.ranikun.finance.journal.web

import jakarta.servlet.http.HttpServletRequest
import com.fasterxml.jackson.annotation.JsonInclude
import labs.ranikun.finance.journal.application.JournalRetrospective
import labs.ranikun.finance.journal.application.JournalRetrospectiveService
import labs.ranikun.finance.journal.application.RetrospectiveContent
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@JsonInclude(JsonInclude.Include.ALWAYS)
data class JournalRetrospectiveResponse(
    val retrospectiveId: String,
    val journalId: String,
    val sourceRevision: Long,
    val createdAt: String,
    val body: String,
    val outcomeObservation: String?,
    val decisionQuality: String?,
    val nextCheck: String?,
)

data class JournalRetrospectivePageResponse(
    val items: List<JournalRetrospectiveResponse>,
    val hasMore: Boolean,
)

@RestController
@ConditionalOnProperty("finance.journal.retrospectives.enabled", havingValue = "true")
@RequestMapping("/finance/journals/{journalId}/retrospectives")
class JournalRetrospectiveController(
    private val service: JournalRetrospectiveService,
    private val idempotencyKeyValidator: IdempotencyKeyValidator,
) {
    @GetMapping
    fun list(@PathVariable journalId: String): JournalRetrospectivePageResponse {
        val page = service.list(parseId(journalId, "journalId"))
        return JournalRetrospectivePageResponse(page.items.map { it.toResponse() }, page.hasMore)
    }

    @GetMapping("/{retrospectiveId}")
    fun detail(
        @PathVariable journalId: String,
        @PathVariable retrospectiveId: String,
    ): JournalRetrospectiveResponse =
        service.detail(parseId(journalId, "journalId"), parseId(retrospectiveId, "retrospectiveId")).toResponse()

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun append(
        @PathVariable journalId: String,
        servletRequest: HttpServletRequest,
        @RequestBody request: Map<String, Any?>,
    ): ResponseEntity<JournalRetrospectiveResponse> {
        val id = parseId(journalId, "journalId")
        val key = idempotencyKeyValidator.validate(servletRequest)
        val result = service.append(id, key, parseContent(request))
        return ResponseEntity.created(URI.create("/finance/journals/$id/retrospectives/${result.retrospectiveId}"))
            .body(result.toResponse())
    }

    private fun parseContent(request: Map<String, Any?>): RetrospectiveContent {
        if (request.keys.any { it !in FIELDS }) invalid("request")
        val rawRevision = request["sourceRevision"]
        if (rawRevision !is Int && rawRevision !is Long) invalid("sourceRevision")
        val revision = (rawRevision as Number).toLong()
        if (revision !in 0..MAX_REVISION) invalid("sourceRevision")

        return RetrospectiveContent(
            revision,
            parseText(request["body"], "body", 4000) ?: invalid("body"),
            parseText(request["outcomeObservation"], "outcomeObservation", 2000),
            parseText(request["decisionQuality"], "decisionQuality", 2000),
            parseText(request["nextCheck"], "nextCheck", 2000),
        )
    }

    private fun parseText(value: Any?, field: String, limit: Int): String? {
        if (value == null) return null
        val raw = value as? String ?: invalid(field)
        if (raw.length > limit || '\u0000' in raw) invalid(field)
        return raw.trim().takeIf(String::isNotEmpty)
    }

    private fun parseId(raw: String, field: String): UUID =
        if (UUID_PATTERN.matches(raw)) UUID.fromString(raw) else invalid(field)

    private fun invalid(field: String): Nothing =
        throw InvalidJournalRequestException(listOf(JournalFieldError(field, "invalid")))

    private fun JournalRetrospective.toResponse() = JournalRetrospectiveResponse(
        retrospectiveId.toString(), journalId.toString(), content.sourceRevision, createdAt.toString(),
        content.body, content.outcomeObservation, content.decisionQuality, content.nextCheck,
    )

    private companion object {
        const val MAX_REVISION = 9007199254740991L
        val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val FIELDS = setOf("sourceRevision", "body", "outcomeObservation", "decisionQuality", "nextCheck")
    }
}
