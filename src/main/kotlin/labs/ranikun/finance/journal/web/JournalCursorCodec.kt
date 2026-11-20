package labs.ranikun.finance.journal.web

import com.fasterxml.jackson.annotation.JsonProperty
import labs.ranikun.finance.journal.application.JournalCursor
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.Base64
import java.util.UUID

@Component
class JournalCursorCodec(
    private val jsonMapper: JsonMapper,
) {

    fun encode(cursor: JournalCursor): String {
        require((cursor.version == VERSION && cursor.type == null) || (cursor.version == 2 && cursor.type in setOf("investment", "study"))) { "Unsupported cursor version" }
        val payload = CursorPayload(
            version = cursor.version,
            occurredAtUtc = cursor.occurredAtUtc.toString(),
            journalId = cursor.journalId.toString(),
        )
        val bytes = if (cursor.type == null) jsonMapper.writeValueAsBytes(payload) else jsonMapper.writeValueAsBytes(FilteredCursorPayload(cursor.version, cursor.occurredAtUtc.toString(), cursor.journalId.toString(), cursor.type))
        return URL_ENCODER.encodeToString(bytes)
    }

    fun decode(rawCursor: String): JournalCursor {
        if (!BASE64_URL_PATTERN.matches(rawCursor)) {
            throw IllegalArgumentException("Invalid cursor encoding")
        }

        val decoded = try {
            URL_DECODER.decode(rawCursor)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid cursor encoding", exception)
        }
        val decodedJson = decoded.toString(UTF_8)
        if (decodedJson.any(Char::isWhitespace)) {
            throw IllegalArgumentException("Cursor JSON must be compact")
        }

        val node = try {
            jsonMapper.readTree(decoded)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid cursor JSON", exception)
        }
        if (!node.isObject || node.size() !in 3..4 || !node.has("version") ||
            !node.has("occurredAtUtc") || !node.has("journalId")
        ) {
            throw IllegalArgumentException("Invalid cursor shape")
        }

        val versionNode = node.get("version")
        if (!versionNode.isInt || versionNode.intValue() !in 1..2) {
            throw IllegalArgumentException("Unsupported cursor version")
        }
        val version = versionNode.intValue()
        val type = if (version == 2 && node.size() == 4) requiredString(node, "type") else null
        if ((version == 1 && node.size() != 3) || (version == 2 && type !in setOf("investment", "study"))) {
            throw IllegalArgumentException("Invalid cursor filter")
        }
        val occurredAtUtc = requiredString(node, "occurredAtUtc")
        if (!occurredAtUtc.endsWith("Z")) {
            throw IllegalArgumentException("Cursor time must be UTC")
        }
        val occurredAtInstant = try {
            Instant.parse(occurredAtUtc)
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid cursor time", exception)
        }
        val journalId = parseCanonicalUuid(requiredString(node, "journalId"))

        if (!decoded.contentEquals(jsonMapper.writeValueAsBytes(node))) {
            throw IllegalArgumentException("Cursor JSON must be canonical")
        }
        return JournalCursor(version, occurredAtInstant, journalId, type)
    }

    private fun requiredString(node: JsonNode, fieldName: String): String =
        node.get(fieldName)?.stringValue()
            ?: throw IllegalArgumentException("Cursor field must be a string: $fieldName")

    private fun parseCanonicalUuid(rawJournalId: String): UUID {
        if (!CANONICAL_UUID_PATTERN.matches(rawJournalId)) {
            throw IllegalArgumentException("Invalid cursor Journal ID")
        }
        return try {
            UUID.fromString(rawJournalId)
        } catch (exception: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid cursor Journal ID", exception)
        }
    }

    private data class CursorPayload(
        @JsonProperty("version") val version: Int,
        @JsonProperty("occurredAtUtc") val occurredAtUtc: String,
        @JsonProperty("journalId") val journalId: String,
    )

    private data class FilteredCursorPayload(
        @JsonProperty("version") val version: Int,
        @JsonProperty("occurredAtUtc") val occurredAtUtc: String,
        @JsonProperty("journalId") val journalId: String,
        @JsonProperty("type") val type: String,
    )

    private companion object {
        const val VERSION = 1
        val URL_ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val URL_DECODER: Base64.Decoder = Base64.getUrlDecoder()
        val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
        val CANONICAL_UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
        )
    }
}
