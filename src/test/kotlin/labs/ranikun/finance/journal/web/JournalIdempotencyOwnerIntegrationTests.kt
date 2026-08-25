package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.http.MediaType

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class, MutableCurrentUserTestConfiguration::class)
@ActiveProfiles("test")
class JournalIdempotencyOwnerIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var currentUser: MutableCurrentUserPort

    @BeforeEach
    fun clearRows() {
        currentUser.current = IdentityUserId("owner-one")
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
    }

    @Test
    fun sameKeyAndPayloadAreIndependentAcrossOwners() {
        val first = mockMvc.perform(createRequest())
            .andExpect(status().isCreated)
            .andReturn()
        val firstId = first.response.getHeader("Location")

        currentUser.current = IdentityUserId("owner-two")
        val second = mockMvc.perform(createRequest())
            .andExpect(status().isCreated)
            .andReturn()
        val secondId = second.response.getHeader("Location")

        assertThat(firstId).isNotEqualTo(secondId)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journals", Int::class.java))
            .isEqualTo(2)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_idempotency_records", Int::class.java))
            .isEqualTo(2)
    }

    private fun createRequest() = post("/finance/journals")
        .contentType(MediaType.APPLICATION_JSON)
        .header("Idempotency-Key", "same-key-different-owner")
        .content(
            """
            {
              "type": "investment",
              "assetName": "ETF",
              "occurredAt": "2026-08-12T14:30:15.123",
              "timeZone": "Asia/Seoul",
              "action": "buy",
              "reasoning": "same payload"
            }
            """.trimIndent(),
        )
}

@TestConfiguration(proxyBeanMethods = false)
class MutableCurrentUserTestConfiguration {

    @Bean
    @Primary
    fun mutableCurrentUserPort(): MutableCurrentUserPort = MutableCurrentUserPort()
}

class MutableCurrentUserPort : CurrentUserPort {

    var current: IdentityUserId = IdentityUserId("owner-one")

    override fun currentUserId(): IdentityUserId = current
}
