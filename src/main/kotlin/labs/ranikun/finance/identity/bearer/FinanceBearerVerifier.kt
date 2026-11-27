package labs.ranikun.finance.identity.bearer

import io.jsonwebtoken.Jwts
import labs.ranikun.finance.identity.application.IdentityUserId
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.util.Date
import java.util.UUID

/** Trust policy must be provided by the integrating service; no issuer or claim default exists. */
data class FinanceBearerPolicy(
    val issuer: String,
    val audience: String,
    val accessTypeClaim: String,
    val accessTypeValue: String,
    val publicKey: RSAPublicKey,
) {
    init {
        require(listOf(issuer, audience, accessTypeClaim, accessTypeValue).all { it.isNotBlank() })
        require(publicKey.modulus.bitLength() >= 2048)
    }
}

/** Authoritative revocation lookup must fail closed if unavailable. */
fun interface FinanceRevocationPort {
    fun isActive(token: String): Boolean
}

class FinanceBearerVerifier(
    private val policy: FinanceBearerPolicy,
    private val revocation: FinanceRevocationPort,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun verify(token: String): IdentityUserId? = try {
        if (token.length !in 1..8192) return null
        val parser = Jwts.parser()
            .sig().clear().add(Jwts.SIG.RS256).and()
            .verifyWith(policy.publicKey)
            .clock { Date.from(clock.instant()) }
            .requireIssuer(policy.issuer)
            .requireAudience(policy.audience)
            .build()
        val claims = parser.parseSignedClaims(token).payload
        val subject = claims.subject ?: return null
        val id = UUID.fromString(subject)
        if (id.toString() != subject || claims.expiration == null ||
            !claims.expiration.toInstant().isAfter(clock.instant()) ||
            claims[policy.accessTypeClaim] != policy.accessTypeValue || !revocation.isActive(token)) return null
        IdentityUserId(subject)
    } catch (_: Exception) {
        null
    }
}
