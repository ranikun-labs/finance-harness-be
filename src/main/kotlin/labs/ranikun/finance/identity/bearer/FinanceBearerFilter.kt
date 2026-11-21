package labs.ranikun.finance.identity.bearer

import jakarta.servlet.FilterChain
import jakarta.servlet.Filter
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.DispatcherType
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

internal const val FINANCE_BEARER_PRINCIPAL = "labs.ranikun.finance.identity.verified-bearer-user"

/** This candidate is deliberately not registered as a bean or route. */
class FinanceBearerFilter(private val verifier: FinanceBearerVerifier) : Filter {
    override fun doFilter(rawRequest: ServletRequest, rawResponse: ServletResponse, chain: FilterChain) {
        val request = rawRequest as? HttpServletRequest ?: return chain.doFilter(rawRequest, rawResponse)
        val response = rawResponse as? HttpServletResponse ?: return chain.doFilter(rawRequest, rawResponse)
        val path = request.requestURI.removePrefix(request.contextPath)
        if (!path.startsWith("/finance/journals") && !request.servletPath.startsWith("/finance/journals")) {
            chain.doFilter(request, response)
            return
        }
        val forbidden = listOf(
            "x-user-id", "x-crm-user-id", "x-identity-user-id", "x-organization-id",
            "x-role", "x-public-id", "x-service-id", "x-service-secret", "x-gateway-secret",
        )
        val names = request.headerNames?.toList().orEmpty().map(String::lowercase)
        val authorization = request.getHeaders("Authorization").toList()
        val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        val itemPath = Regex("^/finance/journals/$uuid$")
        val retrospectiveDetailPath = Regex("^/finance/journals/$uuid/retrospectives/$uuid$")
        val allowed = when {
            path == "/finance/journals" -> request.method in setOf("GET", "POST")
            path == "/finance/journals/deleted" -> request.method == "GET"
            itemPath.matches(path) -> request.method in setOf("GET", "PATCH")
            path.endsWith("/delete") || path.endsWith("/restore") -> request.method == "POST" && itemPath.matches(path.substringBeforeLast('/'))
            path.endsWith("/retrospectives") -> request.method in setOf("GET", "POST") && itemPath.matches(path.removeSuffix("/retrospectives"))
            retrospectiveDetailPath.matches(path) -> request.method == "GET"
            else -> false
        }
        if (!allowed || request.dispatcherType != DispatcherType.REQUEST ||
            (request.servletPath.isNotEmpty() && (path != request.servletPath || request.pathInfo != null)) ||
            names.any { it in forbidden || it.startsWith("x-finance-") } || authorization.size != 1 ||
            !authorization.single().startsWith("Bearer ") || request.getAttribute(FINANCE_BEARER_PRINCIPAL) != null) {
            response.sendError(401)
            return
        }
        val id = verifier.verify(authorization.single().removePrefix("Bearer "))
        if (id == null) {
            response.sendError(401)
            return
        }
        request.setAttribute(FINANCE_BEARER_PRINCIPAL, id)
        try { chain.doFilter(request, response) } finally { request.removeAttribute(FINANCE_BEARER_PRINCIPAL) }
    }
}

class FinanceBearerCurrentUserAdapter : CurrentUserPort {
    override fun currentUserId(): IdentityUserId {
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        return request?.getAttribute(FINANCE_BEARER_PRINCIPAL) as? IdentityUserId
            ?: throw IllegalStateException("Verified Finance identity is unavailable")
    }
}
