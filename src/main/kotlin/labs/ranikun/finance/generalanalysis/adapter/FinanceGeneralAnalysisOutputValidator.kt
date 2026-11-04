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
        return fields.none {
            UNSUPPORTED_FINANCIAL_CONTENT_PATTERN.containsMatchIn(it) ||
                DIRECT_SHORT_SYMBOL_ACTION_PATTERN.containsMatchIn(it) ||
                DIRECT_BROKER_ACTION_PATTERN.containsMatchIn(it)
        }
    }

    private companion object {
        const val MAX_FIELD_LENGTH = 2_000
        val UNSUPPORTED_FINANCIAL_CONTENT_PATTERN = Regex(
            "\\b(buy|sell|hold|recommend(?:ation)?|invest(?:ment)?|position size|entry price|exit price|" +
                "price target|target price|price-target|target-price|return forecast|forecasted return|" +
                "expected return|projected return|stock price|market share|revenue|earnings|yield|dividend|" +
                "inflation|interest rate|will rise|will fall|forecast)\\b|" +
                "\\b(acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|sell(?:ing|s)?|hold(?:ing|s)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on|" +
                "invest(?:s|ing|ed)?|trad(?:e|es|ing|ed))\\b.*\\b(shares?|stock|equity|equities|securities)\\b|" +
                "\\b(shares?|stock|equity|equities|securities)\\b.*" +
                "\\b(acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|sell(?:ing|s)?|hold(?:ing|s)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on|" +
                "invest(?:s|ing|ed)?|trad(?:e|es|ing|ed))\\b|" +
                "매수|매도|보유|추천|투자|비중|진입가|청산가|목표가|목표 주가|수익률\\s*예측|" +
                "수익률\\s*전망|주가|시장점유율|매출|실적|배당|금리|물가|오를 것|내릴 것|전망",
            RegexOption.IGNORE_CASE,
        )
        val DIRECT_SHORT_SYMBOL_ACTION_PATTERN = Regex(
            "(?i:\\b(?:acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|sell(?:ing|s)?|hold(?:ing|s)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on|" +
                "invest(?:s|ing|ed)?|trad(?:e|es|ing|ed))\\b\\s+)(?:(?i:" +
                "a|an|the|my|our|your|their|some|any|this|that|new|more|better|basic|useful|valuable|practical|" +
                "advanced|fractional|existing|available|individual|particular|currently|registered|preferred|" +
                "common|publicly\\s+traded|low[- ]priced|small[- ]cap|large[- ]cap|penny|in|for|with|from|about|" +
                "into|on|at|to|by|via|of|off|up|over|under|after|before" +
                ")\\s+)*(?:(?-i:[A-Z][A-Z0-9.-]{1,5})(?!\\s+(?i:(?:(?:design|technical|basic|advanced|new|practical)\\s+)*(?:skills?|knowledge|learning|experience|training|" +
                "course|courses|tutorial|tutorials|documentation|document|query|queries|programming|system|" +
                "systems|software|service|services|tool|tools|framework|frameworks|concept|concepts|docs|" +
                "certification|certifications|credentials?)\\b))|(?!(?i:" +
                "a|an|the|my|our|your|their|some|any|this|that|new|more|better|basic|useful|valuable|practical|" +
                "advanced|fractional|existing|available|individual|particular|currently|registered|preferred|" +
                "common|publicly\\s+traded|low[- ]priced|small[- ]cap|large[- ]cap|penny|key|keys|it|time|data|" +
                "food|lunch|dinner|coffee|book|phone|house|team|group|task|goal|plan|item|items|skill|skills|" +
                "knowledge|learning|experience|context|details|sql|api|pdf|html|json|url|slowly|gradually|" +
                "carefully|regularly|steadily|weekly|monthly|daily|rapidly|promptly" +
                ")\\b)[A-Za-z0-9][A-Za-z0-9.-]{1,5})\\b.*[.!?]?$",
            RegexOption.DOT_MATCHES_ALL,
        )
        val DIRECT_BROKER_ACTION_PATTERN = Regex(
            "(?i:\\b(?:acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|sell(?:ing|s)?|hold(?:ing|s)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on|" +
                "invest(?:s|ing|ed)?|trad(?:e|es|ing|ed))\\b).*\\b(?:broker|brokerage|market order|" +
                "trading account|investment account|stock exchange)\\b",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
