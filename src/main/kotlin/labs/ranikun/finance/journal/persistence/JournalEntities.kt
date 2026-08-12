package labs.ranikun.finance.journal.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "journals")
class JournalEntity(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,
    @Column(name = "identity_user_id", nullable = false, columnDefinition = "TEXT")
    var identityUserId: String,
    @Column(name = "type", nullable = false)
    var type: String,
    @Column(name = "occurred_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    var occurredAt: Instant,
    @JdbcTypeCode(SqlTypes.LOCAL_DATE_TIME)
    @Column(name = "occurred_local_at", nullable = false, columnDefinition = "TIMESTAMP WITHOUT TIME ZONE")
    var occurredLocalAt: LocalDateTime,
    @Column(name = "occurred_time_zone", nullable = false, columnDefinition = "TEXT")
    var occurredTimeZone: String,
    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    var createdAt: Instant,
    @Column(name = "updated_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    var updatedAt: Instant,
)

@Entity
@Table(name = "investment_journals")
class InvestmentJournalEntity(
    @Id
    @Column(name = "journal_id", nullable = false)
    var journalId: UUID,
    @Column(name = "journal_type", nullable = false)
    var journalType: String,
    @Column(name = "asset_name", nullable = false, columnDefinition = "TEXT")
    var assetName: String,
    @Column(name = "action", nullable = false)
    var action: String,
    @Column(name = "reasoning", nullable = false, columnDefinition = "TEXT")
    var reasoning: String,
    @Column(name = "emotion", columnDefinition = "TEXT")
    var emotion: String?,
)

@Entity
@Table(name = "study_journals")
class StudyJournalEntity(
    @Id
    @Column(name = "journal_id", nullable = false)
    var journalId: UUID,
    @Column(name = "journal_type", nullable = false)
    var journalType: String,
    @Column(name = "title", nullable = false, columnDefinition = "TEXT")
    var title: String,
    @Column(name = "key_content", nullable = false, columnDefinition = "TEXT")
    var keyContent: String,
)

@Embeddable
data class StudyOpenQuestionId(
    @Column(name = "study_journal_id", nullable = false)
    var studyJournalId: UUID,
    @Column(name = "position", nullable = false)
    var position: Int,
)

@Entity
@Table(name = "study_open_questions")
class StudyOpenQuestionEntity(
    @EmbeddedId
    var id: StudyOpenQuestionId,
    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    var question: String,
)
