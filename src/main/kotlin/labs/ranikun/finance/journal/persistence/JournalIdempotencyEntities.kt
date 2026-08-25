package labs.ranikun.finance.journal.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "journal_idempotency_records")
class JournalIdempotencyRecordEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long? = null,
    @Column(name = "identity_user_id", nullable = false, columnDefinition = "TEXT")
    var identityUserId: String,
    @Column(name = "operation", nullable = false, columnDefinition = "TEXT")
    var operation: String,
    @Column(name = "idempotency_key", nullable = false, columnDefinition = "TEXT")
    var idempotencyKey: String,
    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "TEXT")
    var requestFingerprint: String,
    @Column(name = "journal_id", nullable = false)
    var journalId: UUID,
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    var createdAt: Instant,
)
