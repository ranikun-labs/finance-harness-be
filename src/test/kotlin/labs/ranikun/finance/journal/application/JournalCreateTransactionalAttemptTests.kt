package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.domain.JournalAction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class JournalCreateTransactionalAttemptTests {

    private val owner = IdentityUserId("user-96")
    private val key = "journal-key"
    private val fingerprint = "v1:sha256:${"a".repeat(64)}"
    private val journalId = UUID.fromString("0190e8f0-8a00-7000-8000-000000000001")
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

    @Test
    fun sameFingerprintReplaysWithoutGeneratingOrPersistingANewJournal() {
        val journalStore = RecordingJournalStore()
        val idempotencyStore = RecordingIdempotencyStore(
            JournalIdempotencyRecord(fingerprint, journalId),
        )
        val generator = RecordingJournalIdGenerator()
        val attempt = attempt(generator, journalStore, idempotencyStore)

        val result = attempt.execute(owner, key, fingerprint, command)

        assertThat(result).isEqualTo(journalId)
        assertThat(generator.calls).isZero()
        assertThat(journalStore.calls).isZero()
        assertThat(idempotencyStore.saveCalls).isZero()
    }

    @Test
    fun differentFingerprintConflictsWithoutGeneratingOrPersistingANewJournal() {
        val journalStore = RecordingJournalStore()
        val idempotencyStore = RecordingIdempotencyStore(
            JournalIdempotencyRecord(fingerprint, journalId),
        )
        val attempt = attempt(RecordingJournalIdGenerator(), journalStore, idempotencyStore)

        assertThatThrownBy {
            attempt.execute(owner, key, "v1:sha256:${"b".repeat(64)}", command)
        }.isInstanceOf(IdempotencyKeyReusedException::class.java)

        assertThat(journalStore.calls).isZero()
        assertThat(idempotencyStore.saveCalls).isZero()
    }

    @Test
    fun missingRecordPersistsJournalAndCompletedRecordInTheAttempt() {
        val journalStore = RecordingJournalStore()
        val idempotencyStore = RecordingIdempotencyStore(null)
        val attempt = attempt(
            RecordingJournalIdGenerator(journalId),
            journalStore,
            idempotencyStore,
        )

        val result = attempt.execute(owner, key, fingerprint, command)

        assertThat(result).isEqualTo(journalId)
        assertThat(journalStore.calls).isEqualTo(1)
        assertThat(idempotencyStore.saveCalls).isEqualTo(1)
        assertThat(idempotencyStore.savedOwner).isEqualTo(owner)
        assertThat(idempotencyStore.savedKey).isEqualTo(key)
        assertThat(idempotencyStore.savedFingerprint).isEqualTo(fingerprint)
        assertThat(idempotencyStore.savedJournalId).isEqualTo(journalId)
    }

    private fun attempt(
        generator: JournalIdGenerator,
        journalStore: JournalStore,
        idempotencyStore: JournalIdempotencyStore,
    ): JournalCreateTransactionalAttempt = JournalCreateTransactionalAttempt(
        journalIdGenerator = generator,
        journalStore = journalStore,
        idempotencyStore = idempotencyStore,
        clock = Clock.fixed(Instant.parse("2026-08-12T05:31:00Z"), ZoneOffset.UTC),
    )

    private class RecordingJournalIdGenerator(
        private val id: UUID = UUID.fromString("0190e8f0-8a00-7000-8000-000000000002"),
    ) : JournalIdGenerator {
        var calls = 0

        override fun generate(): UUID {
            calls += 1
            return id
        }
    }

    private class RecordingJournalStore : JournalStore {
        var calls = 0

        override fun save(journalId: UUID, owner: IdentityUserId, command: JournalCreateCommand) {
            calls += 1
        }
    }

    private class RecordingIdempotencyStore(
        private val existing: JournalIdempotencyRecord?,
    ) : JournalIdempotencyStore {
        var saveCalls = 0
        var savedOwner: IdentityUserId? = null
        var savedKey: String? = null
        var savedFingerprint: String? = null
        var savedJournalId: UUID? = null

        override fun find(
            owner: IdentityUserId,
            operation: String,
            idempotencyKey: String,
        ): JournalIdempotencyRecord? = existing

        override fun save(
            owner: IdentityUserId,
            operation: String,
            idempotencyKey: String,
            requestFingerprint: String,
            journalId: UUID,
            createdAt: Instant,
        ) {
            saveCalls += 1
            savedOwner = owner
            savedKey = idempotencyKey
            savedFingerprint = requestFingerprint
            savedJournalId = journalId
        }
    }
}
