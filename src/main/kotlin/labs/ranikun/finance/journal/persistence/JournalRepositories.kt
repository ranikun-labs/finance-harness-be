package labs.ranikun.finance.journal.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface JournalJpaRepository : JpaRepository<JournalEntity, UUID>

interface InvestmentJournalJpaRepository : JpaRepository<InvestmentJournalEntity, UUID>

interface StudyJournalJpaRepository : JpaRepository<StudyJournalEntity, UUID>

interface StudyOpenQuestionJpaRepository : JpaRepository<StudyOpenQuestionEntity, StudyOpenQuestionId>
