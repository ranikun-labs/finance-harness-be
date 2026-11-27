package labs.ranikun.finance.membership.persistence

import labs.ranikun.finance.membership.application.FinanceMembershipStore
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/** Unwired adapter; no route or registration configuration is created by this slice. */
class PostgresFinanceMembershipStore(private val jdbc: JdbcTemplate) : FinanceMembershipStore {
    override fun ensurePending(verifiedAccountId: UUID): Boolean = jdbc.update(
        """
        INSERT INTO finance_membership_onboarding (account_id, onboarding_status)
        VALUES (?, 'PENDING')
        ON CONFLICT (account_id) DO NOTHING
        """.trimIndent(),
        verifiedAccountId,
    ) == 1
}
