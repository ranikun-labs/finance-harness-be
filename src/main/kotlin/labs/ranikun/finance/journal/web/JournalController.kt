package labs.ranikun.finance.journal.web

import jakarta.validation.Valid
import com.fasterxml.jackson.annotation.JsonInclude
import jakarta.servlet.http.HttpServletRequest
import labs.ranikun.finance.journal.application.JournalDetail
import labs.ranikun.finance.journal.application.JournalCreateApplicationService
import labs.ranikun.finance.journal.application.JournalListRecord
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

sealed interface JournalListItemResponse

data class InvestmentJournalSummaryResponse(
    val journalId: String,
    val type: String,
    val assetName: String,
    val action: String,
    val occurredAt: String,
    val timeZone: String,
) : JournalListItemResponse

data class StudyJournalSummaryResponse(
    val journalId: String,
    val type: String,
    val title: String,
    val occurredAt: String,
    val timeZone: String,
) : JournalListItemResponse

@JsonInclude(JsonInclude.Include.ALWAYS)
data class JournalListResponse(
    val items: List<JournalListItemResponse>,
    val nextCursor: String?,
)

@RestController
@RequestMapping("/finance/journals")
class JournalController(
    private val journalCreateApplicationService: JournalCreateApplicationService,
    private val journalReadApplicationService: JournalReadApplicationService,
    private val journalTimeResolver: JournalTimeResolver,
    private val journalCursorCodec: JournalCursorCodec,
    private val idempotencyKeyValidator: IdempotencyKeyValidator,
) {

    @GetMapping
    fun list(request: HttpServletRequest): JournalListResponse {
        val limitValues = request.getParameterValues("limit")?.toList()
        val cursorValues = request.getParameterValues("cursor")?.toList()
        val limit = parseLimit(singleParameter("limit", limitValues))
        val cursor = singleParameter("cursor", cursorValues)?.let(::parseCursor)
        val page = journalReadApplicationService.findList(limit, cursor)
        return JournalListResponse(
            items = page.items.map { item -> item.toResponse() },
            nextCursor = page.nextCursor?.let(journalCursorCodec::encode),
        )
    }

    @GetMapping("/{journalId}")
    fun detail(@PathVariable journalId: String): JournalDetailResponse =
        journalReadApplicationService.findDetail(parseJournalId(journalId)).toResponse()

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        httpRequest: HttpServletRequest,
        @Valid @RequestBody request: JournalCreateRequest,
    ): ResponseEntity<JournalCreateResponse> {
        val idempotencyKey = idempotencyKeyValidator.validate(httpRequest)
        val journalId = journalCreateApplicationService.create(
            command = request.toCommand(journalTimeResolver),
            idempotencyKey = idempotencyKey,
        )
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

    private fun parseLimit(rawLimit: String?): Int {
        val limit = rawLimit?.toIntOrNull()
        if (limit == null || limit !in MIN_LIMIT..MAX_LIMIT) {
            throw InvalidJournalRequestException(listOf(JournalFieldError("limit", "invalid")))
        }
        return limit
    }

    private fun parseCursor(rawCursor: String): labs.ranikun.finance.journal.application.JournalCursor = try {
        journalCursorCodec.decode(rawCursor)
    } catch (exception: IllegalArgumentException) {
        throw InvalidJournalRequestException(listOf(JournalFieldError("cursor", "invalid")))
    }

    private fun singleParameter(name: String, values: List<String>?): String? {
        if (values.isNullOrEmpty()) {
            return if (name == "limit") DEFAULT_LIMIT.toString() else null
        }
        if (values.size != 1) {
            throw InvalidJournalRequestException(listOf(JournalFieldError(name, "invalid")))
        }
        return values.single()
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

    private fun JournalListRecord.toResponse(): JournalListItemResponse = when (type) {
        "investment" -> InvestmentJournalSummaryResponse(
            journalId = journalId.toString(),
            type = type,
            assetName = requireNotNull(assetName),
            action = requireNotNull(action),
            occurredAt = occurredLocalAt.toWireString(),
            timeZone = timeZone,
        )

        "study" -> StudyJournalSummaryResponse(
            journalId = journalId.toString(),
            type = type,
            title = requireNotNull(title),
            occurredAt = occurredLocalAt.toWireString(),
            timeZone = timeZone,
        )

        else -> error("Unknown persisted Journal type: $type")
    }

    private fun LocalDateTime.toWireString(): String = LOCAL_DATE_TIME_FORMATTER.format(this)

    private fun Instant.toWireString(): String = DateTimeFormatter.ISO_INSTANT.format(this)

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val MIN_LIMIT = 1
        private const val MAX_LIMIT = 100
        private val CANONICAL_UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
        private val LOCAL_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd'T'HH:mm:ss.SSS",
            Locale.ROOT,
        )
    }
}
