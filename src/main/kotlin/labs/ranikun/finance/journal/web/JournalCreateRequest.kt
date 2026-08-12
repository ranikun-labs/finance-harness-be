package labs.ranikun.finance.journal.web

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.Nulls
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import labs.ranikun.finance.journal.application.InvalidJournalTimeException
import labs.ranikun.finance.journal.application.JournalCreateCommand
import labs.ranikun.finance.journal.application.JournalTimeResolver
import labs.ranikun.finance.journal.domain.JournalAction
import labs.ranikun.finance.journal.domain.JournalEmotion
import labs.ranikun.finance.journal.domain.JournalConstraints

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = InvestmentJournalCreateRequest::class, name = "investment"),
    JsonSubTypes.Type(value = StudyJournalCreateRequest::class, name = "study"),
)
sealed interface JournalCreateRequest {

    fun toCommand(timeResolver: JournalTimeResolver): JournalCreateCommand
}

data class InvestmentJournalCreateRequest(
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotBlank
    @field:Size(max = JournalConstraints.ASSET_NAME_MAX_LENGTH)
    val assetName: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotBlank
    val occurredAt: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotBlank
    val timeZone: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotBlank
    val action: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotBlank
    @field:Size(max = JournalConstraints.REASONING_MAX_LENGTH)
    val reasoning: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    val emotion: String? = null,
) : JournalCreateRequest {

    override fun toCommand(timeResolver: JournalTimeResolver): JournalCreateCommand {
        val rawAssetName = boundedRawText("assetName", assetName, JournalConstraints.ASSET_NAME_MAX_LENGTH)
        val rawOccurredAt = requiredText("occurredAt", occurredAt)
        val rawTimeZone = requiredText("timeZone", timeZone)
        val rawAction = requiredText("action", action)
        val rawReasoning = boundedRawText("reasoning", reasoning, JournalConstraints.REASONING_MAX_LENGTH)
        val rawEmotion = emotion?.let { requiredText("emotion", it) }

        val normalizedAssetName = rawAssetName.trim()
        val normalizedOccurredAt = rawOccurredAt.trim()
        val normalizedTimeZone = rawTimeZone.trim()
        val normalizedAction = rawAction.trim()
        val normalizedReasoning = rawReasoning.trim()
        val normalizedEmotion = rawEmotion?.trim()

        val parsedAction = try {
            JournalAction.fromWire(normalizedAction)
        } catch (exception: IllegalArgumentException) {
            throw InvalidJournalRequestException(listOf(JournalFieldError("action", "invalid_enum")))
        }
        val parsedEmotion = normalizedEmotion?.let {
            try {
                JournalEmotion.fromWire(it)
            } catch (exception: IllegalArgumentException) {
                throw InvalidJournalRequestException(listOf(JournalFieldError("emotion", "invalid_enum")))
            }
        }
        val resolvedTime = try {
            timeResolver.resolve(normalizedOccurredAt, normalizedTimeZone)
        } catch (exception: InvalidJournalTimeException) {
            throw InvalidJournalRequestException(
                listOf(JournalFieldError(exception.fieldName, "invalid")),
            )
        }

        return JournalCreateCommand.Investment(
            assetName = normalizedAssetName,
            action = parsedAction,
            reasoning = normalizedReasoning,
            emotion = parsedEmotion,
            occurredAt = resolvedTime,
        )
    }
}

data class StudyJournalCreateRequest(
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotBlank
    @field:Size(max = JournalConstraints.TITLE_MAX_LENGTH)
    val title: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotBlank
    @field:Size(max = JournalConstraints.KEY_CONTENT_MAX_LENGTH)
    val keyContent: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotBlank
    val occurredAt: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:NotBlank
    val timeZone: String,
    @param:JsonSetter(nulls = Nulls.FAIL)
    @field:Size(max = JournalConstraints.OPEN_QUESTIONS_MAX_COUNT)
    val openQuestions: List<String?>,
) : JournalCreateRequest {

    override fun toCommand(timeResolver: JournalTimeResolver): JournalCreateCommand {
        val rawTitle = boundedRawText("title", title, JournalConstraints.TITLE_MAX_LENGTH)
        val rawKeyContent = boundedRawText("keyContent", keyContent, JournalConstraints.KEY_CONTENT_MAX_LENGTH)
        val rawOccurredAt = requiredText("occurredAt", occurredAt)
        val rawTimeZone = requiredText("timeZone", timeZone)

        if (openQuestions.size > JournalConstraints.OPEN_QUESTIONS_MAX_COUNT) {
            throw InvalidJournalRequestException(listOf(JournalFieldError("openQuestions", "too_many")))
        }

        val rawQuestions = openQuestions.mapIndexed { index, question ->
            val field = "openQuestions[$index]"
            val nonNullQuestion = question
                ?: throw InvalidJournalRequestException(listOf(JournalFieldError(field, "required")))
            boundedRawText(field, nonNullQuestion, JournalConstraints.OPEN_QUESTION_MAX_LENGTH)
        }
        val normalizedTitle = rawTitle.trim()
        val normalizedKeyContent = rawKeyContent.trim()
        val normalizedOccurredAt = rawOccurredAt.trim()
        val normalizedTimeZone = rawTimeZone.trim()
        val normalizedQuestions = rawQuestions.map(String::trim)
        val resolvedTime = try {
            timeResolver.resolve(normalizedOccurredAt, normalizedTimeZone)
        } catch (exception: InvalidJournalTimeException) {
            throw InvalidJournalRequestException(
                listOf(JournalFieldError(exception.fieldName, "invalid")),
            )
        }

        return JournalCreateCommand.Study(
            title = normalizedTitle,
            keyContent = normalizedKeyContent,
            openQuestions = normalizedQuestions,
            occurredAt = resolvedTime,
        )
    }
}

private fun requiredText(field: String, value: String): String {
    if (value.isBlank()) {
        throw InvalidJournalRequestException(listOf(JournalFieldError(field, "required")))
    }
    return value
}

private fun boundedRawText(field: String, value: String, maxLength: Int): String {
    val raw = requiredText(field, value)
    if (raw.length > maxLength) {
        throw InvalidJournalRequestException(listOf(JournalFieldError(field, "too_long")))
    }
    return raw
}
