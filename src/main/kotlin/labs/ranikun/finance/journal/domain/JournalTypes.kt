package labs.ranikun.finance.journal.domain

enum class JournalType(val wireValue: String) {
    INVESTMENT("investment"),
    STUDY("study"),
}

enum class JournalAction(val wireValue: String) {
    INTEREST("interest"),
    WATCHING("watching"),
    BUY("buy"),
    SELL("sell"),
    ;

    companion object {
        fun fromWire(value: String): JournalAction = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown journal action")
    }
}

enum class JournalEmotion(val wireValue: String) {
    FOMO("FOMO"),
    ANXIETY("불안"),
    CONFIDENCE("확신"),
    WAITING("관망"),
    CONFUSION("혼란"),
    ;

    companion object {
        fun fromWire(value: String): JournalEmotion = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown journal emotion")
    }
}
