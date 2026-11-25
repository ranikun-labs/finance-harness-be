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

    // 아래 코드는 진단 없이 FAILED로 끝나던 분기를 구분한다. detail에는 고정 enum 이름이나 HTTP 상태 숫자만 넣는다.
    SHARED_AI_FAILURE,
    HTTP_STATUS,
    NETWORK_ERROR,
    PRE_CALL_FAILURE,
    EXECUTION_EXCEPTION,
    ;

    fun log(analysisRequestId: UUID, startedAtNanos: Long) {
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        LOGGER.warn("analysisRequestId={} elapsedMs={} failureCode={}", analysisRequestId, elapsedMs, name)
    }

    /** 실패 분류 이름처럼 허용된 고정 값만 받는다. 자유 문자열을 넘길 수 없게 Enum으로 제한한다. */
    fun log(analysisRequestId: UUID, startedAtNanos: Long, detail: Enum<*>) =
        logDetail(analysisRequestId, startedAtNanos, detail.name)

    /** HTTP 상태는 유효 범위의 숫자만 기록한다. */
    fun log(analysisRequestId: UUID, startedAtNanos: Long, httpStatus: Int?) {
        if (httpStatus == null || httpStatus !in 100..599) return log(analysisRequestId, startedAtNanos)
        logDetail(analysisRequestId, startedAtNanos, httpStatus.toString())
    }

    private fun logDetail(analysisRequestId: UUID, startedAtNanos: Long, detail: String) {
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        LOGGER.warn(
            "analysisRequestId={} elapsedMs={} failureCode={} detail={}",
            analysisRequestId,
            elapsedMs,
            name,
            detail,
        )
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger(GeneralAnalysisFailureCode::class.java)
    }
}
