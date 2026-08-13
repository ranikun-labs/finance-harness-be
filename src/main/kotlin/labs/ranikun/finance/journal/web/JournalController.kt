package labs.ranikun.finance.journal.web

import jakarta.validation.Valid
import com.fasterxml.jackson.annotation.JsonInclude
import labs.ranikun.finance.journal.application.JournalDetail
import labs.ranikun.finance.journal.application.JournalCreateApplicationService
import labs.ranikun.finance.journal.application.JournalReadApplicationService
import labs.ranikun.finance.journal.application.JournalTimeResolver
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

data class JournalCreateResponse(val journalId: String)

sealed interface JournalDetailResponse

@JsonInclude(JsonInclude.Include.ALWAYS)
data class InvestmentJournalDetailResponse(
    val journalId: String,
    val type: String,
    val occurredAt: String,
    val timeZone: String,
    val createdAt: String,
    val updatedAt: String,
    val assetName: String,
    val action: String,
    val reasoning: String,
    val emotion: String?,
) : JournalDetailResponse

data class StudyJournalDetailResponse(
    val journalId: String,
    val type: String,
    val occurredAt: String,
    val timeZone: String,
    val createdAt: String,
    val updatedAt: String,
    val title: String,
    val keyContent: String,
    val openQuestions: List<String>,
) : JournalDetailResponse

@RestController
@RequestMapping("/finance/journals")
class JournalController(
    private val journalCreateApplicationService: JournalCreateApplicationService,
    private val journalReadApplicationService: JournalReadApplicationService,
    private val journalTimeResolver: JournalTimeResolver,
) {

    @GetMapping("/{journalId}")
    fun detail(@PathVariable journalId: String): JournalDetailResponse =
        journalReadApplicationService.findDetail(parseJournalId(journalId)).toResponse()

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(@Valid @RequestBody request: JournalCreateRequest): ResponseEntity<JournalCreateResponse> {
        val journalId = journalCreateApplicationService.create(request.toCommand(journalTimeResolver))
        return ResponseEntity
            .created(URI.create("/finance/journals/$journalId"))
            .body(JournalCreateResponse(journalId.toString()))
    }

    private fun parseJournalId(rawJournalId: String): UUID {
        if (!CANONICAL_UUID_PATTERN.matches(rawJournalId)) {
            throw InvalidJournalRequestException(listOf(JournalFieldError("journalId", "invalid")))
        }
        return try {
            UUID.fromString(rawJournalId)
        } catch (exception: IllegalArgumentException) {
            throw InvalidJournalRequestException(listOf(JournalFieldError("journalId", "invalid")))
        }
    }

    private fun JournalDetail.toResponse(): JournalDetailResponse = when (this) {
        is JournalDetail.Investment -> InvestmentJournalDetailResponse(
            journalId = journalId.toString(),
            type = "investment",
            occurredAt = occurredLocalAt.toWireString(),
            timeZone = timeZone,
            createdAt = createdAt.toWireString(),
            updatedAt = updatedAt.toWireString(),
            assetName = assetName,
            action = action,
            reasoning = reasoning,
            emotion = emotion,
        )

        is JournalDetail.Study -> StudyJournalDetailResponse(
            journalId = journalId.toString(),
            type = "study",
            occurredAt = occurredLocalAt.toWireString(),
            timeZone = timeZone,
            createdAt = createdAt.toWireString(),
            updatedAt = updatedAt.toWireString(),
            title = title,
            keyContent = keyContent,
            openQuestions = openQuestions,
        )
    }

    private fun LocalDateTime.toWireString(): String = LOCAL_DATE_TIME_FORMATTER.format(this)

    private fun Instant.toWireString(): String = DateTimeFormatter.ISO_INSTANT.format(this)

    companion object {
        private val CANONICAL_UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        private val LOCAL_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd'T'HH:mm:ss.SSS",
            Locale.ROOT,
        )
    }
}
