package labs.ranikun.finance.identity.bearer

import io.jsonwebtoken.Jwts
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import jakarta.servlet.DispatcherType
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.Base64

class FinanceBearerVerifierTest {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = Instant.parse("2026-11-21T00:00:00Z")
    private val policy = FinanceBearerPolicy("issuer", "finance", "type", "access", pair.public as RSAPublicKey)
    private val verifier = FinanceBearerVerifier(policy, FinanceRevocationPort { true }, Clock.fixed(now, ZoneOffset.UTC))
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val other = "22222222-2222-4222-8222-222222222222"
    private fun token(subject: String = owner, issuer: String = "issuer", audience: String = "finance", type: String = "access", expiry: Instant = now.plusSeconds(60)): String =
        Jwts.builder().subject(subject).issuer(issuer).audience().add(audience).and()
            .claim("type", type).expiration(Date.from(expiry)).signWith(pair.private, Jwts.SIG.RS256).compact()

    @Test fun `verified distinct owners and invalid claims fail closed`() {
        assertThat(verifier.verify(token())?.value).isEqualTo(owner)
        assertThat(verifier.verify(token(subject = other))?.value).isEqualTo(other)
        assertThat(verifier.verify(token(issuer = "wrong"))).isNull()
        assertThat(verifier.verify(token(audience = "wrong"))).isNull()
        assertThat(verifier.verify(token(type = "refresh"))).isNull()
        assertThat(verifier.verify(token(expiry = now.minusSeconds(1)))).isNull()
        assertThat(verifier.verify(token(subject = "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"))).isNull()
        val noExpiry = Jwts.builder().subject(owner).issuer("issuer").audience().add("finance").and()
            .claim("type", "access").signWith(pair.private, Jwts.SIG.RS256).compact()
        assertThat(verifier.verify(noExpiry)).isNull()
        val wrongAlgorithm = Jwts.builder().subject(owner).issuer("issuer").audience().add("finance").and()
            .claim("type", "access").expiration(Date.from(now.plusSeconds(60))).signWith(pair.private, Jwts.SIG.RS384).compact()
        assertThat(verifier.verify(wrongAlgorithm)).isNull()
        val attacker = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val wrongKey = Jwts.builder().subject(owner).issuer("issuer").audience().add("finance").and()
            .claim("type", "access").expiration(Date.from(now.plusSeconds(60))).signWith(attacker.private, Jwts.SIG.RS256).compact()
        assertThat(verifier.verify(wrongKey)).isNull()
        assertThat(verifier.verify("bad.jwt.value")).isNull()
        val revoked = FinanceBearerVerifier(policy, FinanceRevocationPort { false }, Clock.fixed(now, ZoneOffset.UTC))
        assertThat(revoked.verify(token())).isNull()
        val unavailable = FinanceBearerVerifier(policy, FinanceRevocationPort { error("offline") }, Clock.fixed(now, ZoneOffset.UTC))
        assertThat(unavailable.verify(token())).isNull()
    }

    @Test fun `journal request attaches then clears user and refuses forged identity`() {
        val request = MockHttpServletRequest("GET", "/finance/journals")
        request.addHeader("Authorization", "Bearer " + token())
        val response = MockHttpServletResponse()
        var seen: String? = null
        val chain = jakarta.servlet.FilterChain { req, _ ->
            RequestContextHolder.setRequestAttributes(ServletRequestAttributes(req as jakarta.servlet.http.HttpServletRequest))
            try { seen = FinanceBearerCurrentUserAdapter().currentUserId().value } finally { RequestContextHolder.resetRequestAttributes() }
        }
        FinanceBearerFilter(verifier).doFilter(request, response, chain)
        assertThat(seen).isEqualTo(owner)
        assertThat(request.getAttribute(FINANCE_BEARER_PRINCIPAL)).isNull()
        val otherRequest = MockHttpServletRequest("GET", "/finance/journals")
        otherRequest.addHeader("Authorization", "Bearer " + token(subject = other))
        FinanceBearerFilter(verifier).doFilter(otherRequest, MockHttpServletResponse(), chain)
        assertThat(seen).isEqualTo(other)
        assertThat(otherRequest.getAttribute(FINANCE_BEARER_PRINCIPAL)).isNull()
        val forged = MockHttpServletRequest("GET", "/finance/journals")
        forged.addHeader("Authorization", "Bearer " + token())
        forged.addHeader("X-User-Id", other)
        val denied = MockHttpServletResponse()
        FinanceBearerFilter(verifier).doFilter(forged, denied, MockFilterChain())
        assertThat(denied.status).isEqualTo(401)
        val forwarded = MockHttpServletRequest("GET", "/finance/journals")
        forwarded.dispatcherType = DispatcherType.FORWARD
        forwarded.addHeader("Authorization", "Bearer " + token())
        val forwardedResponse = MockHttpServletResponse()
        FinanceBearerFilter(verifier).doFilter(forwarded, forwardedResponse, MockFilterChain())
        assertThat(forwardedResponse.status).isEqualTo(401)
        val malicious = MockHttpServletRequest("GET", "/finance/journals/$owner/retrospectives/not-a-uuid")
        malicious.addHeader("Authorization", "Bearer " + token())
        val malformed = MockHttpServletResponse()
        FinanceBearerFilter(verifier).doFilter(malicious, malformed, MockFilterChain())
        assertThat(malformed.status).isEqualTo(401)
        val nested = MockHttpServletRequest("GET", "/finance/journals/$owner/retrospectives/extra/$other")
        nested.addHeader("Authorization", "Bearer " + token())
        val nestedResponse = MockHttpServletResponse()
        FinanceBearerFilter(verifier).doFilter(nested, nestedResponse, MockFilterChain())
        assertThat(nestedResponse.status).isEqualTo(401)
        val exact = MockHttpServletRequest("GET", "/finance/journals/$owner/retrospectives/$other")
        exact.addHeader("Authorization", "Bearer " + token())
        val exactResponse = MockHttpServletResponse()
        FinanceBearerFilter(verifier).doFilter(exact, exactResponse, chain)
        assertThat(exactResponse.status).isEqualTo(200)
    }

    @Test fun `configuration is disabled by default and missing trust cannot start`() {
        val runner = ApplicationContextRunner().withUserConfiguration(FinanceBearerConfiguration::class.java)
        runner.run { assertThat(it).doesNotHaveBean(FinanceBearerVerifier::class.java) }
        runner.withPropertyValues("finance.auth.bearer.enabled=true").run { assertThat(it).hasFailed() }
    }

    @Test fun `complete test wiring rejects production defaults and environment markers`() {
        val runner = ApplicationContextRunner()
            .withUserConfiguration(FinanceBearerConfiguration::class.java)
            .withBean(FinanceRevocationPort::class.java, java.util.function.Supplier { FinanceRevocationPort { true } })
            .withPropertyValues(
                "finance.auth.bearer.enabled=true",
                "finance.auth.bearer.issuer=issuer",
                "finance.auth.bearer.audience=finance",
                "finance.auth.bearer.access-type-claim=type",
                "finance.auth.bearer.access-type-value=access",
                "finance.auth.bearer.rsa-public-key-der-base64=${Base64.getEncoder().encodeToString(pair.public.encoded)}",
            )
        runner.run { assertThat(it).hasSingleBean(FinanceBearerVerifier::class.java) }
        for (property in listOf(
            "spring.profiles.default=production",
            "spring.profiles.active=prod",
            "PLATFORM_ENVIRONMENT=production",
            "platform.metrics.environment= Prod ",
            "FINANCE_METRICS_ENVIRONMENT=production",
            "management.metrics.tags.environment=production",
        )) {
            runner.withPropertyValues(property).run { assertThat(it).hasFailed() }
        }
    }
}
