package labs.ranikun.finance.common.web

import jakarta.servlet.ServletException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.IOException

class RequestIdFilterTests {

    private val filter = RequestIdFilter()

    @Test
    @Throws(ServletException::class, IOException::class)
    fun preservesSafeIncomingRequestIdAndClearsRequestContext() {
        val request = MockHttpServletRequest().apply {
            addHeader(RequestIdFilter.HEADER_NAME, "request-123")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertThat(response.getHeader(RequestIdFilter.HEADER_NAME)).isEqualTo("request-123")
        assertThat(org.slf4j.MDC.get(RequestIdFilter.MDC_KEY)).isNull()
    }

    @Test
    @Throws(ServletException::class, IOException::class)
    fun replacesUnsafeIncomingRequestIdWithGeneratedOpaqueValue() {
        val request = MockHttpServletRequest().apply {
            addHeader(RequestIdFilter.HEADER_NAME, "unsafe request\nvalue")
        }
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        val requestId = response.getHeader(RequestIdFilter.HEADER_NAME)
        assertThat(requestId).isNotBlank()
        assertThat(requestId).matches("[0-9a-f]{32}")
        assertThat(requestId).doesNotContain("\n")
    }
}
