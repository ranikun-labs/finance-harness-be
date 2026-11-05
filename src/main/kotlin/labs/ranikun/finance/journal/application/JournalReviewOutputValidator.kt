package labs.ranikun.finance.journal.application

import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate

fun interface JournalReviewOutputValidator {
    fun isValid(candidate: GeneralAnalysisCandidate, journal: JournalDetail): Boolean
}
