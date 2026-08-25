package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class JournalCreateApplicationServiceTests {

    private val fixedJournalId = UUID.fromString("0190e8f0-8a00-7000-8000-000000000001")
    private val resolvedTime = ResolvedJournalTime(
        occurredAt = Instant.parse("2026-08-12T05:30:00Z"),
        occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 30),
        occurredTimeZone = "Asia/Seoul",
    )

    @Test
    fun ownerComesOnlyFromCurrentUserPortAndInvestmentIsDispatchedToStore() {
        val store = RecordingJournalStore()
        val service = service(store)

        val journalId = service.create(
            JournalCreateCommand.Investment(
                assetName = "ETF",
                action = labs.ranikun.finance.journal.domain.JournalAction.BUY,
                reasoning = "kept the thesis",
                emotion = null,
                occurredAt = resolvedTime,
            ),
            idempotencyKey = "investment-key",
        )

        assertThat(journalId).isEqualTo(fixedJournalId)
        assertThat(store.savedJournalId).isEqualTo(fixedJournalId)
        assertThat(store.savedOwner).isEqualTo(IdentityUserId("shared-user-42"))
        assertThat(store.savedCommand).isInstanceOf(JournalCreateCommand.Investment::class.java)
    }

    @Test
    fun studyDispatchPreservesQuestionOrderAndDuplicates() {
        val store = RecordingJournalStore()
        val service = service(store)
        val questions = listOf("first", "same", "same", "last")

        service.create(
            JournalCreateCommand.Study(
                title = "Study",
                keyContent = "content",
                openQuestions = questions,
                occurredAt = resolvedTime,
            ),
            idempotencyKey = "study-key",
        )

        val savedStudy = store.savedCommand as JournalCreateCommand.Study
        assertThat(savedStudy.openQuestions).containsExactlyElementsOf(questions)
    }

    private fun service(store: RecordingJournalStore): JournalCreateApplicationService {
        val idempotencyStore = RecordingIdempotencyStore()
        return JournalCreateApplicationService(
            currentUserPort = FixedCurrentUserPort(IdentityUserId("shared-user-42")),
            journalCreateFingerprint = JournalCreateFingerprint(),
            journalCreateTransactionalAttempt = JournalCreateTransactionalAttempt(
                journalIdGenerator = FixedJournalIdGenerator(fixedJournalId),
                journalStore = store,
                idempotencyStore = idempotencyStore,
                clock = Clock.fixed(Instant.parse("2026-08-12T05:31:00Z"), ZoneOffset.UTC),
            ),
            journalIdempotencyRecovery = JournalIdempotencyRecovery(idempotencyStore),
            constraintViolationDetector = JournalIdempotencyConstraintViolationDetector(),
        )
    }

    private class FixedCurrentUserPort(private val identityUserId: IdentityUserId) : CurrentUserPort {
        override fun currentUserId(): IdentityUserId = identityUserId
    }

    private class FixedJournalIdGenerator(private val id: UUID) : JournalIdGenerator {
        override fun generate(): UUID = id
    }

    private class RecordingJournalStore : JournalStore {
        lateinit var savedJournalId: UUID
        var savedOwner: IdentityUserId? = null
        lateinit var savedCommand: JournalCreateCommand

        override fun save(
            journalId: UUID,
            owner: IdentityUserId,
            command: JournalCreateCommand,
        ) {
            savedJournalId = journalId
            savedOwner = owner
            savedCommand = command
        }
    }

    private class RecordingIdempotencyStore : JournalIdempotencyStore {
        override fun find(
            owner: IdentityUserId,
            operation: String,
            idempotencyKey: String,
        ): JournalIdempotencyRecord? = null

        override fun save(
            owner: IdentityUserId,
            operation: String,
            idempotencyKey: String,
            requestFingerprint: String,
            journalId: UUID,
            createdAt: Instant,
        ) = Unit
    }
}
