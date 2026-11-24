package labs.ranikun.finance.generalanalysis.application

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.TimeUnit

internal enum class GeneralAnalysisFailureCode {
    HTTP_TIMEOUT,
    RESPONSE_INVALID,
    EVIDENCE_INVALID,
    INTERPRETATION_INVALID,
    OUTPUT_REJECTED,
    ;

    fun log(analysisRequestId: UUID, startedAtNanos: Long) {
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        LOGGER.warn("analysisRequestId={} elapsedMs={} failureCode={}", analysisRequestId, elapsedMs, name)
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(GeneralAnalysisFailureCode::class.java)
    }
}
