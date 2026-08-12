package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.CurrentUserPort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class JournalCreateApplicationService(
    private val currentUserPort: CurrentUserPort,
    private val journalIdGenerator: JournalIdGenerator,
    private val journalStore: JournalStore,
) {

    @Transactional
    fun create(command: JournalCreateCommand): UUID {
        val journalId = journalIdGenerator.generate()
        val owner = currentUserPort.currentUserId()
        journalStore.save(journalId, owner, command)
        return journalId
    }
}
