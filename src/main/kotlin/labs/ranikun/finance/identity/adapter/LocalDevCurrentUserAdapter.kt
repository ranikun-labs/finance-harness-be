package labs.ranikun.finance.identity.adapter

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

@Component
@Profile("local", "test")
@ConditionalOnProperty(
    prefix = "finance.auth",
    name = ["mode"],
    havingValue = "local",
    matchIfMissing = false,
)
class LocalDevCurrentUserAdapter(
    @Value("\${finance.auth.local.identity-user-id}") configuredIdentityUserId: String,
) : CurrentUserPort {

    private val currentUser = IdentityUserId(configuredIdentityUserId)

    override fun currentUserId(): IdentityUserId = currentUser
}
