package labs.ranikun.finance.journal.application

import labs.ranikun.finance.journal.domain.JournalAction
import labs.ranikun.finance.journal.domain.JournalEmotion

sealed interface JournalCreateCommand {

    val occurredAt: ResolvedJournalTime

    data class Investment(
        val assetName: String,
        val action: JournalAction,
        val reasoning: String,
        val emotion: JournalEmotion?,
        override val occurredAt: ResolvedJournalTime,
    ) : JournalCreateCommand

    data class Study(
        val title: String,
        val keyContent: String,
        val openQuestions: List<String>,
        override val occurredAt: ResolvedJournalTime,
    ) : JournalCreateCommand
}
