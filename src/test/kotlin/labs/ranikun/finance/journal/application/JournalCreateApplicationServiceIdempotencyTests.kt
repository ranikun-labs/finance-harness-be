package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.domain.JournalAction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.hibernate.exception.ConstraintViolationException
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class JournalCreateApplicationServiceIdempotencyTests {

    private val owner = IdentityUserId("user-96")
    private val key = "journal-key"
    private val command = JournalCreateCommand.Investment(
        assetName = "ETF",
        action = JournalAction.BUY,
        reasoning = "thesis",
        emotion = null,
        occurredAt = ResolvedJournalTime(
            occurredAt = Instant.parse("2026-08-12T05:30:00Z"),
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 30),
            occurredTimeZone = "Asia/Seoul",
        ),
    )
    private val winnerId = UUID.fromString("0190e8f0-8a00-7000-8000-000000000001")

    @Test
    fun namespaceUniqueLoserLooksUpTheWinnerAfterTheAttemptFails() {
        val store = RacingIdempotencyStore(
            failureConstraint = "uq_journal_idempotency_records_namespace",
            winner = JournalIdempotencyRecord(fingerprint(), winnerId),
        )
        val service = service(store)

        assertThat(service.create(command, key)).isEqualTo(winnerId)
        assertThat(store.findCalls).isEqualTo(2)
    }

    @Test
    fun winnerWithDifferentFingerprintBecomesConflictWithoutExposingItsJournalId() {
        val store = RacingIdempotencyStore(
            failureConstraint = "uq_journal_idempotency_records_namespace",
            winner = JournalIdempotencyRecord("v1:sha256:${"b".repeat(64)}", winnerId),
        )
        val service = service(store)

        assertThatThrownBy { service.create(command, key) }
            .isInstanceOf(IdempotencyKeyReusedException::class.java)
        assertThat(store.findCalls).isEqualTo(2)
    }

    @Test
    fun unrelatedUniqueViolationIsNotTreatedAsAnIdempotencyRace() {
        val store = RacingIdempotencyStore(
            failureConstraint = "pk_journals",
            winner = JournalIdempotencyRecord(fingerprint(), winnerId),
        )
        val service = service(store)

        assertThatThrownBy { service.create(command, key) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(store.findCalls).isEqualTo(1)
    }

    private fun service(store: RacingIdempotencyStore): JournalCreateApplicationService {
        val attempt = JournalCreateTransactionalAttempt(
            journalIdGenerator = FixedJournalIdGenerator(),
            journalStore = NoOpJournalStore(),
            idempotencyStore = store,
            clock = Clock.fixed(Instant.parse("2026-08-12T05:31:00Z"), ZoneOffset.UTC),
        )
        return JournalCreateApplicationService(
            currentUserPort = FixedCurrentUserPort(owner),
            journalCreateFingerprint = JournalCreateFingerprint(),
            journalCreateTransactionalAttempt = attempt,
            journalIdempotencyRecovery = JournalIdempotencyRecovery(store),
            constraintViolationDetector = JournalIdempotencyConstraintViolationDetector(),
        )
    }

    private fun fingerprint(): String = JournalCreateFingerprint().calculate(command)

    private class FixedCurrentUserPort(private val owner: IdentityUserId) : CurrentUserPort {
        override fun currentUserId(): IdentityUserId = owner
    }

    private class FixedJournalIdGenerator : JournalIdGenerator {
        override fun generate(): UUID = UUID.fromString("0190e8f0-8a00-7000-0000-000000000002")
    }

    private class NoOpJournalStore : JournalStore {
        override fun save(journalId: UUID, owner: IdentityUserId, command: JournalCreateCommand) = Unit
    }

    private class RacingIdempotencyStore(
        private val failureConstraint: String,
        private val winner: JournalIdempotencyRecord,
    ) : JournalIdempotencyStore {
        var findCalls = 0

        override fun find(
            owner: IdentityUserId,
            operation: String,
            idempotencyKey: String,
        ): JournalIdempotencyRecord? {
            findCalls += 1
            return if (findCalls == 1) null else winner
        }

        override fun save(
            owner: IdentityUserId,
            operation: String,
            idempotencyKey: String,
            requestFingerprint: String,
            journalId: UUID,
            createdAt: Instant,
        ) {
            throw DataIntegrityViolationException(
                "duplicate key",
                ConstraintViolationException(
                    "duplicate key",
                    SQLException("duplicate key", "23505"),
                    failureConstraint,
                ),
            )
        }
    }
}
