package labs.ranikun.finance.identity.web

import jakarta.servlet.DispatcherType
import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import labs.ranikun.finance.identity.application.IdentityUserId
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Reference-only verifier for the bounded Gateway to Finance D1 contract.
 * Registration is deliberately restricted to the explicit fixture profile.
 */
class FinanceTrustedContextFilter(
    serviceSecret: String,
) : Filter {

    private val expectedSecret = serviceSecret.toByteArray(StandardCharsets.UTF_8)

    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val httpRequest = request as? HttpServletRequest ?: run {
            chain.doFilter(request, response)
            return
        }
        val httpResponse = response as? HttpServletResponse ?: run {
            chain.doFilter(request, response)
            return
        }

        if (!targetsFinanceJournal(httpRequest)) {
            chain.doFilter(request, response)
            return
        }

        if (httpRequest.dispatcherType != DispatcherType.REQUEST) {
            reject(httpResponse)
            return
        }

        val path = requestPath(httpRequest)
        if (path == null || !isSupportedRoute(httpRequest.method, path)) {
            reject(httpResponse)
            return
        }

        if (httpRequest.getAttribute(FinanceTrustedContextRequestAttributes.IDENTITY_USER_ID) != null) {
            reject(httpResponse)
            return
        }

        val headers = readEnvelope(httpRequest) ?: run {
            reject(httpResponse)
            return
        }

        // Check the caller credential before decoding or exposing the user subject.
        val suppliedSecret = headers.getValue(HEADER_SECRET).toByteArray(StandardCharsets.UTF_8)
        if (!MessageDigest.isEqual(expectedSecret, suppliedSecret)) {
            reject(httpResponse)
            return
        }

        if (headers.getValue(HEADER_VERSION) != CONTEXT_VERSION ||
            headers.getValue(HEADER_SERVICE_ID) != SERVICE_ID ||
            headers.getValue(HEADER_AUDIENCE) != AUDIENCE
        ) {
            reject(httpResponse)
            return
        }

        val subject = decodeSubject(headers.getValue(HEADER_SUBJECT)) ?: run {
            reject(httpResponse)
            return
        }
        val identityUserId = try {
            IdentityUserId(subject)
        } catch (_: IllegalArgumentException) {
            reject(httpResponse)
            return
        }

        httpRequest.setAttribute(FinanceTrustedContextRequestAttributes.IDENTITY_USER_ID, identityUserId)
        try {
            chain.doFilter(request, response)
        } finally {
            httpRequest.removeAttribute(FinanceTrustedContextRequestAttributes.IDENTITY_USER_ID)
        }
    }

    private fun readEnvelope(request: HttpServletRequest): Map<String, String>? {
        val names = request.headerNames?.toList().orEmpty()
        val normalizedNames = names.map(String::lowercase)
        if (normalizedNames.any { it.startsWith("x-finance-") && it !in REQUIRED_HEADERS_LOWERCASE }) {
            return null
        }
        if (normalizedNames.any { it in FORBIDDEN_IDENTITY_HEADERS }) return null

        val values = LinkedHashMap<String, String>(REQUIRED_HEADERS.size)
        var totalBytes = 0
        for (name in REQUIRED_HEADERS) {
            val supplied = request.getHeaders(name)?.toList().orEmpty()
            if (supplied.size != 1) return null
            val value = supplied.single()
            if (value.contains(',')) return null
            totalBytes += value.toByteArray(StandardCharsets.UTF_8).size
            if (totalBytes > MAX_CONTEXT_BYTES) return null
            values[name] = value
        }
        return values
    }

    private fun decodeSubject(encoded: String): String? {
        if (encoded.isEmpty() || !CANONICAL_BASE64URL.matches(encoded)) return null
        val decoded = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (decoded.size !in 1..MAX_SUBJECT_BYTES) return null
        if (Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != encoded) return null
        val subject = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(decoded))
                .toString()
        } catch (_: Exception) {
            return null
        }
        if (subject.isBlank() || subject.any { it == '\r' || it == '\n' || it == '\u0000' }) return null
        return subject
    }

    private fun targetsFinanceJournal(request: HttpServletRequest): Boolean {
        val rawPath = request.requestURI.removePrefix(request.contextPath)
        return rawPath.startsWith(JOURNAL_PATH) || request.servletPath.startsWith(JOURNAL_PATH)
    }

    private fun requestPath(request: HttpServletRequest): String? {
        val rawPath = request.requestURI.removePrefix(request.contextPath)
        val servletPath = request.servletPath
        if (servletPath.isNotEmpty() && (rawPath != servletPath || request.pathInfo != null)) return null
        return rawPath
    }

    private fun isSupportedRoute(method: String, path: String): Boolean = when {
        path == JOURNAL_PATH -> method == "GET" || method == "POST"
        LOWERCASE_JOURNAL_ID.matches(path) -> method == "GET"
        else -> false
    }

    private fun reject(response: HttpServletResponse) {
        response.status = HttpServletResponse.SC_FORBIDDEN
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.contentType = "application/json"
        response.writer.write("{\"code\":\"untrusted_finance_context\",\"message\":\"The request could not be trusted.\"}")
    }

    companion object {
        const val CONTEXT_VERSION = "1"
        const val SERVICE_ID = "gateway-app"
        const val AUDIENCE = "finance-journal"

        const val HEADER_VERSION = "X-Finance-Context-Version"
        const val HEADER_SERVICE_ID = "X-Finance-Service-Id"
        const val HEADER_SECRET = "X-Finance-Service-Secret"
        const val HEADER_AUDIENCE = "X-Finance-Audience"
        const val HEADER_SUBJECT = "X-Finance-Subject"

        const val JOURNAL_PATH = "/finance/journals"
        const val MAX_CONTEXT_BYTES = 2048
        const val MAX_SUBJECT_BYTES = 256

        private val REQUIRED_HEADERS = listOf(
            HEADER_VERSION,
            HEADER_SERVICE_ID,
            HEADER_SECRET,
            HEADER_AUDIENCE,
            HEADER_SUBJECT,
        )
        private val REQUIRED_HEADERS_LOWERCASE = REQUIRED_HEADERS.map(String::lowercase).toSet()
        private val FORBIDDEN_IDENTITY_HEADERS = setOf(
            "authorization",
            "cookie",
            "x-service-id",
            "x-service-secret",
            "x-user-id",
            "x-crm-user-id",
            "x-identity-user-id",
        )
        private val CANONICAL_BASE64URL = Regex("[A-Za-z0-9_-]+")
        private val LOWERCASE_JOURNAL_ID = Regex(
            "/finance/journals/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )
    }
}

internal object FinanceTrustedContextRequestAttributes {
    const val IDENTITY_USER_ID = "labs.ranikun.finance.identity.trusted-context.identity-user-id"
}
