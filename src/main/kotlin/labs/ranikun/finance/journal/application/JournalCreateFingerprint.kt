package labs.ranikun.finance.journal.application

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Locale
import org.springframework.stereotype.Component

@Component
class JournalCreateFingerprint {

    fun calculate(command: JournalCreateCommand): String {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { writer ->
                writer.writeString(FORMAT_NAME)
                writer.writeInt(SCHEMA_VERSION)
                writer.writeString(command.typeWireValue())
                writer.writeString(OCCURRED_AT_FORMATTER.format(command.occurredAt.occurredLocalAt))
                writer.writeString(command.occurredAt.occurredTimeZone)
                when (command) {
                    is JournalCreateCommand.Investment -> {
                        writer.writeString(command.assetName)
                        writer.writeString(command.action.wireValue)
                        writer.writeString(command.reasoning)
                        writer.writeNullableString(command.emotion?.wireValue)
                    }

                    is JournalCreateCommand.Study -> {
                        writer.writeString(command.title)
                        writer.writeString(command.keyContent)
                        writer.writeStringList(command.openQuestions)
                    }
                }
            }
        }.toByteArray()

        return "v1:sha256:${HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))}"
    }

    private fun JournalCreateCommand.typeWireValue(): String = when (this) {
        is JournalCreateCommand.Investment -> "investment"
        is JournalCreateCommand.Study -> "study"
    }

    private fun DataOutputStream.writeString(value: String) {
        val encoded = value.toByteArray(UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeByte(NULL_MARKER)
        } else {
            writeByte(PRESENT_MARKER)
            writeString(value)
        }
    }

    private fun DataOutputStream.writeStringList(values: List<String>) {
        writeInt(values.size)
        values.forEach { value -> writeString(value) }
    }

    companion object {
        private const val FORMAT_NAME = "journal-create-fingerprint"
        private const val SCHEMA_VERSION = 1
        private const val NULL_MARKER = 0
        private const val PRESENT_MARKER = 1
        private val OCCURRED_AT_FORMATTER = DateTimeFormatter.ofPattern(
            "uuuu-MM-dd'T'HH:mm:ss.SSS",
            Locale.ROOT,
        )
    }
}
