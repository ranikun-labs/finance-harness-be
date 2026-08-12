package labs.ranikun.finance.journal.application

import labs.ranikun.finance.identity.application.IdentityUserId
import java.util.UUID

interface JournalStore {

    fun save(
        journalId: UUID,
        owner: IdentityUserId,
        command: JournalCreateCommand,
    )
}
