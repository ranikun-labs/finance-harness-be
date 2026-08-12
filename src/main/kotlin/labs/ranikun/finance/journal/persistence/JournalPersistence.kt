package labs.ranikun.finance.journal.persistence

import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.application.JournalCreateCommand
import labs.ranikun.finance.journal.application.JournalStore
import labs.ranikun.finance.journal.domain.JournalType
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Repository
class JpaJournalStore(
    private val journalRepository: JournalJpaRepository,
    private val investmentJournalRepository: InvestmentJournalJpaRepository,
    private val studyJournalRepository: StudyJournalJpaRepository,
    private val studyOpenQuestionRepository: StudyOpenQuestionJpaRepository,
    private val clock: Clock,
) : JournalStore {

    override fun save(
        journalId: UUID,
        owner: IdentityUserId,
        command: JournalCreateCommand,
    ) {
        val now = Instant.now(clock)
        val journalType = when (command) {
            is JournalCreateCommand.Investment -> JournalType.INVESTMENT
            is JournalCreateCommand.Study -> JournalType.STUDY
        }
        val time = command.occurredAt

        journalRepository.saveAndFlush(
            JournalEntity(
                id = journalId,
                identityUserId = owner.value,
                type = journalType.wireValue,
                occurredAt = time.occurredAt,
                occurredLocalAt = time.occurredLocalAt,
                occurredTimeZone = time.occurredTimeZone,
                createdAt = now,
                updatedAt = now,
            ),
        )

        when (command) {
            is JournalCreateCommand.Investment -> investmentJournalRepository.saveAndFlush(
                InvestmentJournalEntity(
                    journalId = journalId,
                    journalType = journalType.wireValue,
                    assetName = command.assetName,
                    action = command.action.wireValue,
                    reasoning = command.reasoning,
                    emotion = command.emotion?.wireValue,
                ),
            )

            is JournalCreateCommand.Study -> {
                studyJournalRepository.saveAndFlush(
                    StudyJournalEntity(
                        journalId = journalId,
                        journalType = journalType.wireValue,
                        title = command.title,
                        keyContent = command.keyContent,
                    ),
                )
                command.openQuestions.forEachIndexed { position, question ->
                    studyOpenQuestionRepository.saveAndFlush(
                        StudyOpenQuestionEntity(
                            id = StudyOpenQuestionId(journalId, position),
                            question = question,
                        ),
                    )
                }
            }
        }
    }
}
