package labs.ranikun.finance.identity

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.adapter.LocalDevCurrentUserAdapter
import labs.ranikun.finance.identity.adapter.TrustedContextCurrentUserAdapter
import labs.ranikun.finance.identity.application.CurrentUserPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc

/**
 * The dev profile must boot with the trusted-context receiver (no fixture profile, no local adapter),
 * and direct requests without the Gateway envelope must be refused on every Finance route.
 */
@AutoConfigureMockMvc
@SpringBootTest(
    properties = [
        "finance.auth.trusted-context.service-secret=Finance-Dev-Test-Only-Public-Dummy-Secret-2026",
    ],
)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("dev")
class FinanceDevProfileContextTests {

    @Autowired
    lateinit var currentUserPort: CurrentUserPort

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun devProfileWiresTrustedContextAndNoLocalIdentityAdapter() {
        assertThat(currentUserPort).isInstanceOf(TrustedContextCurrentUserAdapter::class.java)
        assertThat(currentUserPort).isNotInstanceOf(LocalDevCurrentUserAdapter::class.java)
    }

    @Test
    fun directRequestsWithoutGatewayEnvelopeAreRefusedOnJournalAndGeneralAnalysisRoutes() {
        mockMvc.perform(get("/finance/journals"))
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"code":"untrusted_finance_context"}"""))
        mockMvc.perform(post("/finance/journals").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/finance/general-analysis/questions").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"code":"untrusted_finance_context"}"""))
    }
}
