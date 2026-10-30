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
        RISK_DECISION_PATTERN.containsMatchIn(question) -> PolicyClassification.RISK_DECISION
        UNKNOWN_INTENT_PATTERN.containsMatchIn(question) -> PolicyClassification.UNKNOWN
        SAFE_GENERAL_PATTERN.containsMatchIn(question) -> PolicyClassification.SAFE_GENERAL
        else -> PolicyClassification.UNKNOWN
    }

    private companion object {
        val RISK_DECISION_PATTERN = Regex(
            "\\b(buy|sell|hold|purchase|recommend(?:ation)?|price target|target price|should i invest|" +
                "should i make an investment|good investment|investment recommendation)\\b|" +
                "\\b(acquir(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|buy(?:ing)?)\\b.{0,80}" +
                "\\b(shares?|stock|equities|securities)\\b.{0,80}" +
                "\\b(wise|prudent|makes sense|sensible|a good idea|a good choice|a smart move)\\b|" +
                "\\b(wise|prudent|makes sense|sensible|a good idea|a good choice|a smart move)\\b.{0,80}" +
                "\\b(acquir(?:e|ing|ed|es)|purchas(?:e|ing|ed|es)|buy(?:ing)?)\\b.{0,80}" +
                "\\b(shares?|stock|equities|securities)\\b|" +
                "매수|매도|사도 돼|팔아도 돼|보유해|추천|목표가|얼마까지 오를",
            RegexOption.IGNORE_CASE,
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
