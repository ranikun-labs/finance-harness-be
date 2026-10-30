package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisOutputValidator
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Fail-closed checks for the evidence-limited first-slice output shape. */
@Component
@Profile("!prod & !production & (local | test)")
class FinanceGeneralAnalysisOutputValidator : GeneralAnalysisOutputValidator {
    override fun isValid(candidate: GeneralAnalysisCandidate): Boolean {
        val fields = listOf(candidate.summary, candidate.evidenceNote) +
            candidate.observations + candidate.assumptions + candidate.nextChecks
        if (candidate.summary.isBlank() || candidate.evidenceNote.isBlank()) return false
        if (candidate.observations.isEmpty() || candidate.assumptions.isEmpty() || candidate.nextChecks.isEmpty()) {
            return false
        }
        if (fields.any { it.isBlank() || it.length > MAX_FIELD_LENGTH }) return false
        return fields.none { UNSUPPORTED_FINANCIAL_CONTENT_PATTERN.containsMatchIn(it) }
    }

    private companion object {
        const val MAX_FIELD_LENGTH = 2_000
        val UNSUPPORTED_FINANCIAL_CONTENT_PATTERN = Regex(
            "\\b(buy|sell|hold|recommend(?:ation)?|invest(?:ment)?|position size|entry price|exit price|" +
                "price target|target price|price-target|target-price|return forecast|forecasted return|" +
                "expected return|projected return|stock price|market share|revenue|earnings|yield|dividend|" +
                "inflation|interest rate|will rise|will fall|forecast)\\b|" +
                "\\b(acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on)\\b.{0,80}" +
                "\\b(shares?|stock|equities|securities)\\b.{0,80}" +
                "\\b(wise|prudent|makes sense|sensible|(?:a )?(?:good|smart|wise|sound)\\s+" +
                "(?:idea|choice|move|decision))\\b|" +
                "\\b(wise|prudent|makes sense|sensible|(?:a )?(?:good|smart|wise|sound)\\s+" +
                "(?:idea|choice|move|decision))\\b.{0,80}" +
                "\\b(acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on)\\b.{0,80}" +
                "\\b(shares?|stock|equities|securities)\\b|" +
                "매수|매도|보유|추천|투자|비중|진입가|청산가|목표가|목표 주가|수익률\\s*예측|" +
                "수익률\\s*전망|주가|시장점유율|매출|실적|배당|금리|물가|오를 것|내릴 것|전망",
            RegexOption.IGNORE_CASE,
        )
    }
}
