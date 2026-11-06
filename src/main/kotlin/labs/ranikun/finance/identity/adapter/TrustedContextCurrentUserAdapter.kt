package labs.ranikun.finance.identity.adapter

import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.identity.web.FinanceTrustedContextRequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** Reads only the immutable principal installed by FinanceTrustedContextFilter. */
class TrustedContextCurrentUserAdapter : CurrentUserPort {

    override fun currentUserId(): IdentityUserId {
        val requestAttributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
            ?: throw IllegalStateException("Trusted Finance identity is unavailable for this request")
        return requestAttributes.request
            .getAttribute(FinanceTrustedContextRequestAttributes.IDENTITY_USER_ID) as? IdentityUserId
            ?: throw IllegalStateException("Trusted Finance identity is unavailable for this request")
    }
}
