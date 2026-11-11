package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionInput
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionResult
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisLens
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Deterministic integration seam for the first slice; no external model or provider is called. */
@Component
@Profile("!prod & !production & (local | test)")
@ConditionalOnProperty(prefix = "finance.shared-ai", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class DeterministicGeneralAnalysisExecutionAdapter : GeneralAnalysisExecutionPort {
    override fun execute(input: GeneralAnalysisExecutionInput): GeneralAnalysisExecutionResult {
        if (input.lens != GeneralAnalysisLens.GENERAL_ANALYSIS) return GeneralAnalysisExecutionResult.TechnicalFailure
        return GeneralAnalysisExecutionResult.Success(
            GeneralAnalysisCandidate(
                summary = "This is a general analysis of the question you submitted.",
                observations = listOf("Only the submitted question was provided as input."),
                assumptions = listOf("No external facts or data were supplied."),
                nextChecks = listOf("Verify any relevant facts with a primary source."),
                evidenceNote = "Evidence: user-provided question only; no external source was consulted.",
            ),
        )
    }
}
