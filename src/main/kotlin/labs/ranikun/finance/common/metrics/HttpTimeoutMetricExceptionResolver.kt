package labs.ranikun.finance.common.metrics

import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.QueryTimeoutException as JpaQueryTimeoutException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.dao.QueryTimeoutException as SpringQueryTimeoutException
import org.springframework.stereotype.Component
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import java.net.SocketTimeoutException
import java.sql.SQLTimeoutException
import java.util.concurrent.TimeoutException

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class HttpTimeoutMetricExceptionResolver(
    private val meterRegistry: MeterRegistry,
) : HandlerExceptionResolver {

    override fun resolveException(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any?,
        ex: Exception,
    ): ModelAndView? {
        val timeout = causeChain(ex).firstOrNull(::isTimeout) ?: return null
        if (request.getAttribute(COUNTED_ATTRIBUTE) == true) return null

        request.setAttribute(COUNTED_ATTRIBUTE, true)
        val cause = when (timeout) {
            is AsyncRequestTimeoutException -> "async_request"
            is SocketTimeoutException -> "socket"
            is SQLTimeoutException, is JpaQueryTimeoutException, is SpringQueryTimeoutException -> "database"
            is java.sql.SQLException -> "database"
            else -> "operation"
        }
        meterRegistry.counter(TIMEOUT_METRIC, "cause", cause).increment()

        // Let the existing MVC exception handlers decide the HTTP response.
        return null
    }

    private fun causeChain(error: Throwable): Sequence<Throwable> = sequence {
        val pending = ArrayDeque<Throwable>()
        val visited = HashSet<Throwable>()
        pending.add(error)
        repeat(MAX_CAUSE_DEPTH) {
            if (pending.isEmpty()) return@sequence
            val current = pending.removeFirst()
            if (!visited.add(current)) return@repeat
            yield(current)
            current.cause?.let(pending::addLast)
            if (current is java.sql.SQLException) current.nextException?.let(pending::addLast)
        }
    }

    private fun isTimeout(error: Throwable): Boolean =
        error is AsyncRequestTimeoutException ||
            error is SocketTimeoutException ||
            error is SQLTimeoutException ||
            error is java.sql.SQLException && PostgresTimeoutClassifier.isTimeout(error) ||
            error is JpaQueryTimeoutException ||
            error is SpringQueryTimeoutException ||
            error is TimeoutException

    companion object {
        private const val TIMEOUT_METRIC = "ranikun.http.timeouts"
        private const val COUNTED_ATTRIBUTE = "labs.ranikun.finance.metrics.timeout-counted"
        private const val MAX_CAUSE_DEPTH = 16
    }
}
