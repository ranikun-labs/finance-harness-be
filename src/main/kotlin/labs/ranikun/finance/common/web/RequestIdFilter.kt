package labs.ranikun.finance.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.SecureRandom

@Component
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = request.getHeader(HEADER_NAME)
            ?.takeIf(SAFE_REQUEST_ID::matches)
            ?: generatedRequestId()

        response.setHeader(HEADER_NAME, requestId)
        MDC.put(MDC_KEY, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER_NAME = "X-Request-ID"
        const val MDC_KEY = "requestId"

        private val RANDOM = SecureRandom()
        private val SAFE_REQUEST_ID = Regex("[A-Za-z0-9._-]{1,128}")

        private fun generatedRequestId(): String = ByteArray(16)
            .also(RANDOM::nextBytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
