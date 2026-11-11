package labs.ranikun.finance.identity

import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter
import labs.ranikun.finance.identity.web.FinanceTrustedContextRequestAttributes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

class FinanceTrustedContextFilterBoundaryTests {

    @Test
    fun downstreamFailureClearsRequestIdentityBeforeNextDifferentUserRequest() {
        val filter = FinanceTrustedContextFilter(publicFixtureSecret())
        val firstRequest = request("owner-a")
        val firstResponse = MockHttpServletResponse()
        var firstIdentity: IdentityUserId? = null

        assertThatThrownBy {
            filter.doFilter(firstRequest, firstResponse, FilterChain { request, _ ->
                firstIdentity = request.getAttribute(FinanceTrustedContextRequestAttributes.IDENTITY_USER_ID)
                    as IdentityUserId
                throw IllegalStateException("downstream fixture failure")
            })
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(firstIdentity).isEqualTo(IdentityUserId("owner-a"))
        assertThat(firstRequest.getAttribute(FinanceTrustedContextRequestAttributes.IDENTITY_USER_ID)).isNull()

        val secondRequest = request("owner-b")
        val secondResponse = MockHttpServletResponse()
        var secondIdentity: IdentityUserId? = null
        filter.doFilter(secondRequest, secondResponse, FilterChain { request, _ ->
            secondIdentity = request.getAttribute(FinanceTrustedContextRequestAttributes.IDENTITY_USER_ID)
                as IdentityUserId
        })

        assertThat(secondIdentity).isEqualTo(IdentityUserId("owner-b"))
        assertThat(secondRequest.getAttribute(FinanceTrustedContextRequestAttributes.IDENTITY_USER_ID)).isNull()
    }

    @Test
    fun eachMissingHeaderAndWrongServiceOrAudienceAreGenericForbiddenBeforeTheChain() {
        val filter = FinanceTrustedContextFilter(publicFixtureSecret())
        val validHeaders = headers("owner-a")
        val invalidCases = REQUIRED_HEADERS.map { missing ->
            "missing $missing" to (validHeaders - missing)
        } + listOf(
            "wrong service id" to (validHeaders + (FinanceTrustedContextFilter.HEADER_SERVICE_ID to "other-service")),
            "wrong audience" to (validHeaders + (FinanceTrustedContextFilter.HEADER_AUDIENCE to "other-audience")),
        )

        invalidCases.forEach { (_, headers) ->
            val request = MockHttpServletRequest("GET", FinanceTrustedContextFilter.JOURNAL_PATH)
            headers.forEach { (name, value) -> request.addHeader(name, value) }
            val response = MockHttpServletResponse()
            var chainCalled = false

            filter.doFilter(request, response, FilterChain { _: ServletRequest, _: ServletResponse ->
                chainCalled = true
            })

            assertThat(response.status).isEqualTo(403)
            assertThat(response.contentAsString)
                .isEqualTo("{\"code\":\"untrusted_finance_context\",\"message\":\"The request could not be trusted.\"}")
            assertThat(chainCalled).isFalse()
            assertThat(request.getAttribute(FinanceTrustedContextRequestAttributes.IDENTITY_USER_ID)).isNull()
        }
    }

    private fun request(subject: String): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", FinanceTrustedContextFilter.JOURNAL_PATH)
        headers(subject).forEach { (name, value) -> request.addHeader(name, value) }
        return request
    }

    private fun headers(subject: String): Map<String, String> = mapOf(
        FinanceTrustedContextFilter.HEADER_VERSION to FinanceTrustedContextFilter.CONTEXT_VERSION,
        FinanceTrustedContextFilter.HEADER_SERVICE_ID to FinanceTrustedContextFilter.SERVICE_ID,
        FinanceTrustedContextFilter.HEADER_SECRET to publicFixtureSecret(),
        FinanceTrustedContextFilter.HEADER_AUDIENCE to FinanceTrustedContextFilter.AUDIENCE,
        FinanceTrustedContextFilter.HEADER_SUBJECT to Base64.getUrlEncoder().withoutPadding()
            .encodeToString(subject.toByteArray(StandardCharsets.UTF_8)),
    )

    private fun publicFixtureSecret(): String = Properties().run {
        this@FinanceTrustedContextFilterBoundaryTests.javaClass.classLoader
            .getResourceAsStream("application-finance-trusted-context-reference.properties")
            .use { stream -> load(requireNotNull(stream)) }
        getProperty("finance.auth.trusted-context.service-secret")
    }

    companion object {
        private val REQUIRED_HEADERS = listOf(
            FinanceTrustedContextFilter.HEADER_VERSION,
            FinanceTrustedContextFilter.HEADER_SERVICE_ID,
            FinanceTrustedContextFilter.HEADER_SECRET,
            FinanceTrustedContextFilter.HEADER_AUDIENCE,
            FinanceTrustedContextFilter.HEADER_SUBJECT,
        )
    }
}
