package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisLens
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisLensRouter
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisPolicyGuard
import labs.ranikun.finance.generalanalysis.application.PolicyClassification
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("!prod & !production & (local | test)")
class FinanceGeneralAnalysisPolicyGuard : GeneralAnalysisPolicyGuard {
    override fun classify(question: String): PolicyClassification = when {
        RISK_DECISION_PATTERN.containsMatchIn(question) ||
            DIRECT_EQUITY_ACTION_PATTERN.containsMatchIn(question) ||
            DIRECT_SHORT_SYMBOL_ACTION_PATTERN.containsMatchIn(question) ||
            DIRECT_LOWERCASE_SYMBOL_REQUEST_PATTERN.containsMatchIn(question) -> PolicyClassification.RISK_DECISION
        UNKNOWN_INTENT_PATTERN.containsMatchIn(question) ||
            AMBIGUOUS_LOWERCASE_SYMBOL_ACTION_PATTERN.containsMatchIn(question) -> PolicyClassification.UNKNOWN
        SAFE_GENERAL_PATTERN.containsMatchIn(question) -> PolicyClassification.SAFE_GENERAL
        else -> PolicyClassification.UNKNOWN
    }

    private companion object {
        val RISK_DECISION_PATTERN = Regex(
            "\\b(buy|sell|hold|purchase|recommend(?:ation)?|price target|target price|should i invest|" +
                "should i make an investment|good investment|investment recommendation)\\b|" +
                "\\b(acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on)\\b.{0,80}" +
                "\\b(shares?|stock|equity|equities|securities)\\b.{0,80}" +
                "\\b(wise|prudent|makes sense|sensible|(?:a )?(?:good|smart|wise|sound)\\s+" +
                "(?:idea|choice|move|decision))\\b|" +
                "\\b(wise|prudent|makes sense|sensible|(?:a )?(?:good|smart|wise|sound)\\s+" +
                "(?:idea|choice|move|decision))\\b.{0,80}" +
                "\\b(acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on)\\b.{0,80}" +
                "\\b(shares?|stock|equity|equities|securities)\\b|" +
                "매수|매도|사도 돼|팔아도 돼|보유해|추천|목표가|얼마까지 오를",
            RegexOption.IGNORE_CASE,
        )
        val DIRECT_EQUITY_ACTION_PATTERN = Regex(
            "\\b(acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|sell(?:ing|s)?|hold(?:ing|s)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on|" +
                "invest(?:s|ing|ed)?|trad(?:e|es|ing|ed))\\b.*\\b(shares?|stock|equity|equities|securities)\\b|" +
                "\\b(shares?|stock|equity|equities|securities)\\b.*" +
                "\\b(acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|sell(?:ing|s)?|hold(?:ing|s)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on|" +
                "invest(?:s|ing|ed)?|trad(?:e|es|ing|ed))\\b",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val DIRECT_SHORT_SYMBOL_ACTION_PATTERN = Regex(
            "(?i:\\b(?:acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|" +
                "buy(?:ing)?|sell(?:ing|s)?|hold(?:ing|s)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on|" +
                "invest(?:s|ing|ed)?|trad(?:e|es|ing|ed))\\b\\s+)(?:(?i:" +
                "a|an|the|my|our|your|their|some|any|this|that|new|more|better|basic|useful|valuable|practical|" +
                "advanced|fractional|existing|available|individual|particular|currently|registered|preferred|" +
                "common|publicly\\s+traded|low[- ]priced|small[- ]cap|large[- ]cap|penny|in|for|with|from|about|" +
                "into|on|at|to|by|via|of|off|up|over|under|after|before" +
                ")\\s+)*+(?-i:[A-Z][A-Z0-9.-]{1,5})(?!\\s+(?i:(?:(?:design|technical|basic|advanced|new|practical)\\s+)*(?:skills?|knowledge|learning|experience|training|" +
                "course|courses|tutorial|tutorials|documentation|document|query|queries|programming|system|" +
                "systems|software|service|services|tool|tools|framework|frameworks|concept|concepts|docs|" +
                "certification|certifications|credentials?|keys?)\\b))\\b.*[?.!]?$",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val DIRECT_LOWERCASE_SYMBOL_REQUEST_PATTERN = Regex(
            "(?i:^\\s*how\\s+(?:do|does|did|can|could|would|should)\\s+(?:i|we|you|one)\\b).*?\\b(?i:" +
                "acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|buy(?:ing)?|" +
                "sell(?:ing|s)?|hold(?:ing|s)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on|" +
                "invest(?:s|ing|ed)?|trad(?:e|es|ing|ed))\\b\\s+(?:(?i:" +
                "a|an|the|my|our|your|their|some|any|this|that|new|more|better|basic|useful|valuable|practical|" +
                "advanced|fractional|existing|available|individual|particular|currently|registered|preferred|" +
                "common|publicly\\s+traded|low[- ]priced|small[- ]cap|large[- ]cap|penny|in|for|with|from|about|" +
                "into|on|at|to|by|via|of|off|up|over|under|after|before" +
                ")\\s+)*+(?!(?i:a|an|the|my|our|your|their|some|any|this|that|new|more|better|basic|useful|valuable|" +
                "practical|advanced|fractional|existing|available|individual|particular|currently|registered|preferred|" +
                "common|publicly\\s+traded|low[- ]priced|small[- ]cap|large[- ]cap|penny|key|keys|car|cars|it|time|data|" +
                "food|lunch|dinner|coffee|book|phone|house|team|group|task|goal|plan|item|items|skill|skills|" +
                "knowledge|learning|experience|context|details|sql|api|pdf|html|json|url|slowly|gradually|" +
                "carefully|regularly|steadily|weekly|monthly|daily|rapidly|promptly)\\b)[a-z][a-z0-9.-]{1,5}\\b.*[?.!]?$",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val AMBIGUOUS_LOWERCASE_SYMBOL_ACTION_PATTERN = Regex(
            "(?i:^\\s*(?:how\\s+to|(?:explain|compare|describe|analy[sz]e|summari[sz]e)\\b.*?\\b(?:how|ways?|steps?)\\s+to|" +
                "(?:explain|compare|describe|analy[sz]e|summari[sz]e)\\b.*?\\bhow\\s+(?:(?:do|does|did|can|could|would|should)\\s+(?:i|we|you|one)|(?:i|we|you|one)\\s+(?:do|does|did|can|could|would|should))|" +
                "(?:explain|compare|describe|analy[sz]e|summari[sz]e)\\b.*?\\b(?:the\\s+)?process\\s+of|" +
                "what\\s+(?:is|are|was|were)\\b.*?\\b(?:ways?|steps?)\\s+to)\\b).*?\\b(?i:" +
                "acquir(?:e|ing|ed|es)|accumulat(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|buy(?:ing)?|" +
                "sell(?:ing|s)?|hold(?:ing|s)?|add(?:ing|ed|s)?|load(?:ing)?\\s+up\\s+on|" +
                "invest(?:s|ing|ed)?|trad(?:e|es|ing|ed))\\b\\s+(?:(?i:" +
                "a|an|the|my|our|your|their|some|any|this|that|new|more|better|basic|useful|valuable|practical|" +
                "advanced|fractional|existing|available|individual|particular|currently|registered|preferred|" +
                "common|publicly\\s+traded|low[- ]priced|small[- ]cap|large[- ]cap|penny|in|for|with|from|about|" +
                "into|on|at|to|by|via|of|off|up|over|under|after|before" +
                ")\\s+)*+(?!(?i:a|an|the|my|our|your|their|some|any|this|that|new|more|better|basic|useful|valuable|" +
                "practical|advanced|fractional|existing|available|individual|particular|currently|registered|preferred|" +
                "common|publicly\\s+traded|low[- ]priced|small[- ]cap|large[- ]cap|penny|key|keys|car|cars|it|time|data|" +
                "food|lunch|dinner|coffee|book|phone|house|team|group|task|goal|plan|item|items|skill|skills|" +
                "knowledge|learning|experience|context|details|sql|api|pdf|html|json|url|slowly|gradually|" +
                "carefully|regularly|steadily|weekly|monthly|daily|rapidly|promptly)\\b)[a-z][a-z0-9.-]{1,5}\\b.*[?.!]?$",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val UNKNOWN_INTENT_PATTERN = Regex(
            "\\b(whether\\s+(i|we)\\s+should|should i get in|should i make a move|should i do it|" +
                "is now the right time|is this the right time|is now a good time|should i act|" +
                "what should i do now)\\b|\\bwould\\b.{0,120}\\bbe\\s+(wise|prudent|a good idea)\\b|" +
                "지금 들어가도 돼|지금 해도 돼|지금 하는 게 맞아",
            RegexOption.IGNORE_CASE,
        )
        val SAFE_GENERAL_PATTERN = Regex(
            "^\\s*(what\\s+(is|are|was|were|facts|information|factors|evidence|does|do)\\b|" +
                "how\\s+(do|does|did|can|to)\\b|why\\s+(does|did|is|are)\\b|" +
                "explain(?:ation)?\\b|summari[sz]e\\b|compare\\b|describe\\b|analy[sz]e\\b)",
            RegexOption.IGNORE_CASE,
        )
    }
}

@Component
@Profile("!prod & !production & (local | test)")
class SafeGeneralAnalysisLensRouter : GeneralAnalysisLensRouter {
    override fun route(classification: PolicyClassification): GeneralAnalysisLens {
        check(classification == PolicyClassification.SAFE_GENERAL) {
            "Only safe general intent can be routed."
        }
        return GeneralAnalysisLens.GENERAL_ANALYSIS
    }
}
