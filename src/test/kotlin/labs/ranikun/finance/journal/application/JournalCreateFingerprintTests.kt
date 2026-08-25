package labs.ranikun.finance.journal.application

import labs.ranikun.finance.journal.domain.JournalAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime

class JournalCreateFingerprintTests {

    private val fingerprint = JournalCreateFingerprint()
    private val resolvedTime = ResolvedJournalTime(
        occurredAt = Instant.parse("2026-08-12T05:30:15.123Z"),
        occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 30, 15, 123_000_000),
        occurredTimeZone = "Asia/Seoul",
    )

    @Test
    fun investmentFingerprintUsesTheVersionedCanonicalBinaryRepresentation() {
        val command = JournalCreateCommand.Investment(
            assetName = "ETF",
            action = JournalAction.BUY,
            reasoning = "line one\nline  two",
            emotion = null,
            occurredAt = resolvedTime,
        )

        assertThat(fingerprint.calculate(command))
            .isEqualTo("v1:sha256:b4ffd802501f76e3e108b2e447b2535e4da7d12f5840353bd166a4b5950b10ad")
    }

    @Test
    fun studyFingerprintPreservesQuestionOrderAndDuplicates() {
        val command = JournalCreateCommand.Study(
            title = "Study",
            keyContent = "content",
            openQuestions = listOf("first", "same", "same", "last"),
            occurredAt = resolvedTime.copy(
                occurredAt = Instant.parse("2026-08-12T05:30:00Z"),
                occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 30),
            ),
        )

        assertThat(fingerprint.calculate(command))
            .isEqualTo("v1:sha256:cb1b2e4bb8caf56c2c931c973b19327fea788146ad89d77afa47ddd43bc9c8ac")
    }

    @Test
    fun semanticallyDifferentCommandsHaveDifferentFingerprints() {
        val first = JournalCreateCommand.Investment(
            assetName = "ETF",
            action = JournalAction.BUY,
            reasoning = "thesis",
            emotion = null,
            occurredAt = resolvedTime,
        )
        val second = first.copy(reasoning = "different thesis")

        assertThat(fingerprint.calculate(first)).isNotEqualTo(fingerprint.calculate(second))
    }
}
