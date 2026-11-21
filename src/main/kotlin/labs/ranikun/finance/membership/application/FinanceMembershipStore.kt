package labs.ranikun.finance.membership.application

import java.util.UUID

/** Internal persistence seam. Callers must authenticate and verify the account first. */
interface FinanceMembershipStore {
    /** Record onboarding intent once. Existing rows and their state must remain intact. */
    fun ensurePending(verifiedAccountId: UUID): Boolean
}
