package financeholding

import labs.ranikun.finance.holding.HoldingController
import labs.ranikun.finance.identity.adapter.TrustedContextCurrentUserAdapter
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter.Companion.AUDIENCE
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter.Companion.HEADER_AUDIENCE
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter.Companion.HEADER_SECRET
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter.Companion.HEADER_SERVICE_ID
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter.Companion.HEADER_SUBJECT
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter.Companion.HEADER_VERSION
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter.Companion.HOLDING_AUDIENCE
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter.Companion.HOLDING_PATH
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.nio.file.Path
import java.util.Base64

class HoldingReferenceTests(@param:TempDir private val directory: Path) {

    @Test
    fun verifiedAccountsAreIsolatedAndRecoverTickersFromReopenedStore() {
        val server = controller()
        server.perform(get(HOLDING_PATH)).andExpect(status().isForbidden)
        server.perform(put(HOLDING_PATH).contentType(MediaType.APPLICATION_JSON).content("""{"ticker":"AAPL"}"""))
            .andExpect(status().isForbidden)
        server.perform(get(HOLDING_PATH).principal("a").header("X-Identity-User-Id", "b"))
            .andExpect(status().isForbidden)
        server.perform(get(HOLDING_PATH).principal("a", AUDIENCE)).andExpect(status().isForbidden)
        server.perform(get(HOLDING_PATH + "/a").principal("b")).andExpect(status().isForbidden)
        server.perform(save("a", """{"ticker":"AAPL"}"""))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
        server.perform(get(HOLDING_PATH).principal("b"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ticker").doesNotExist())
        server.perform(get("$HOLDING_PATH?ownerId=a").principal("b")).andExpect(status().isBadRequest)
        server.perform(save("b", """{"ownerId":"a","ticker":"TSLA"}""")).andExpect(status().isBadRequest)
        server.perform(save("b", """{"ticker":"MSFT"}""")).andExpect(status().isOk)

        val reopened = controller()
        reopened.perform(get(HOLDING_PATH).principal("a")).andExpect(jsonPath("$.ticker").value("AAPL"))
        reopened.perform(get(HOLDING_PATH).principal("b")).andExpect(jsonPath("$.ticker").value("MSFT"))
    }

    private fun controller(): MockMvc = MockMvcBuilders
        .standaloneSetup(HoldingController(TrustedContextCurrentUserAdapter(), directory.toString()))
        .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(
            FinanceTrustedContextFilter(FIXTURE_CALLER, holdingReferenceEnabled = true),
        )
        .build()

    private fun save(user: String, body: String) =
        put(HOLDING_PATH).principal(user).contentType(MediaType.APPLICATION_JSON).content(body)

    private fun MockHttpServletRequestBuilder.principal(subject: String, audience: String = HOLDING_AUDIENCE) =
        header(HEADER_VERSION, "1")
            .header(HEADER_SERVICE_ID, "gateway-app")
            .header(HEADER_SECRET, FIXTURE_CALLER)
            .header(HEADER_AUDIENCE, audience)
            .header(HEADER_SUBJECT, Base64.getUrlEncoder().withoutPadding().encodeToString(subject.toByteArray()))

    companion object {
        private const val FIXTURE_CALLER = "public-test-credential-for-holding-reference"
    }
}
