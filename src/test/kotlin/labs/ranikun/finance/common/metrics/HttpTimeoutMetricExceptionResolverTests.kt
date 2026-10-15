package labs.ranikun.finance.common.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.sql.SQLTimeoutException
import java.sql.SQLException

class HttpTimeoutMetricExceptionResolverTests {

    private val registry = SimpleMeterRegistry()
    private val resolver = HttpTimeoutMetricExceptionResolver(registry)

    @Test
    fun `counts a real timeout cause once without changing exception handling`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val timeout = IllegalStateException("database operation failed", SQLTimeoutException("statement timed out"))

        assertThat(resolver.resolveException(request, response, null, timeout)).isNull()
        assertThat(resolver.resolveException(request, response, null, timeout)).isNull()

        assertThat(registry.find("ranikun.http.timeouts").tag("cause", "database").counter()?.count())
            .isEqualTo(1.0)
    }

    @Test
    fun `does not infer timeouts from ordinary server errors`() {
        val response = MockHttpServletResponse()
        response.status = 500
        resolver.resolveException(
            MockHttpServletRequest(),
            response,
            null,
            IllegalStateException("ordinary application failure"),
        )

        assertThat(registry.find("ranikun.http.timeouts").counter()).isNull()
    }

    @Test
    fun `counts PostgreSQL statement timeout but not explicit user cancellation`() {
        val timeout = IllegalStateException(
            "database operation failed",
            SQLException("ERROR: canceling statement due to statement timeout", "57014"),
        )
        resolver.resolveException(MockHttpServletRequest(), MockHttpServletResponse(), null, timeout)

        val cancellation = IllegalStateException(
            "database operation failed",
            SQLException("ERROR: canceling statement due to user request", "57014"),
        )
        resolver.resolveException(MockHttpServletRequest(), MockHttpServletResponse(), null, cancellation)

        assertThat(registry.find("ranikun.http.timeouts").tag("cause", "database").counter()?.count())
            .isEqualTo(1.0)
    }

    @Test
    fun `does not count a gateway timeout response without a timeout exception`() {
        val response = MockHttpServletResponse()
        response.status = 504
        resolver.resolveException(
            MockHttpServletRequest(),
            response,
            null,
            IllegalStateException("ordinary upstream response"),
        )

        assertThat(registry.find("ranikun.http.timeouts").counter()).isNull()
    }
}
