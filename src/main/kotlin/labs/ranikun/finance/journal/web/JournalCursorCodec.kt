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
        require(cursor.version == VERSION) { "Unsupported cursor version" }
        val payload = CursorPayload(
            version = cursor.version,
            occurredAtUtc = cursor.occurredAtUtc.toString(),
            journalId = cursor.journalId.toString(),
        )
        return URL_ENCODER.encodeToString(jsonMapper.writeValueAsBytes(payload))
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
        if (!node.isObject || node.size() != 3 || !node.has("version") ||
            !node.has("occurredAtUtc") || !node.has("journalId")
        ) {
            throw IllegalArgumentException("Invalid cursor shape")
        }

        val versionNode = node.get("version")
        if (!versionNode.isInt || versionNode.intValue() != VERSION) {
            throw IllegalArgumentException("Unsupported cursor version")
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
        return JournalCursor(VERSION, occurredAtInstant, journalId)
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
