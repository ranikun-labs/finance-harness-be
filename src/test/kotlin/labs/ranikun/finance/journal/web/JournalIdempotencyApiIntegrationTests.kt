package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class JournalIdempotencyApiIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearJournalRows() {
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
    }

    @Test
    fun sameInvestmentKeyReplaysTheOriginal201LocationAndBody() {
        val first = mockMvc.perform(createRequest("same-investment", investmentJson()))
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/finance/journals/")))
            .andExpect(jsonPath("$.journalId").isNotEmpty)
            .andReturn()
        val journalId = first.response.getHeader(HttpHeaders.LOCATION).orEmpty().substringAfterLast('/')

        repeat(3) {
            val replay = mockMvc.perform(createRequest("same-investment", investmentJson()))
                .andExpect(status().isCreated)
                .andExpect(header().string(HttpHeaders.LOCATION, "/finance/journals/$journalId"))
                .andExpect(jsonPath("$.journalId").value(journalId))
                .andReturn()

            assertThat(replay.response.contentAsString).isEqualTo(first.response.contentAsString)
        }
        assertThat(count("journals")).isEqualTo(1)
        assertThat(count("journal_idempotency_records")).isEqualTo(1)
    }

    @Test
    fun sameStudyKeyReplaysTheOriginalJournal() {
        val first = mockMvc.perform(createRequest("same-study", studyJson()))
            .andExpect(status().isCreated)
            .andReturn()
        val journalId = first.response.getHeader(HttpHeaders.LOCATION).orEmpty().substringAfterLast('/')

        mockMvc.perform(createRequest("same-study", studyJson()))
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.LOCATION, "/finance/journals/$journalId"))
            .andExpect(jsonPath("$.journalId").value(journalId))

        assertThat(count("journals")).isEqualTo(1)
        assertThat(count("study_journals")).isEqualTo(1)
        assertThat(count("study_open_questions")).isEqualTo(2)
        assertThat(count("journal_idempotency_records")).isEqualTo(1)
    }

    @Test
    fun sameKeyWithDifferentPayloadReturnsConflictAndKeepsTheOriginalJournal() {
        val first = mockMvc.perform(createRequest("different-payload", investmentJson()))
            .andExpect(status().isCreated)
            .andReturn()
        val journalId = first.response.getHeader(HttpHeaders.LOCATION).orEmpty().substringAfterLast('/')

        mockMvc.perform(createRequest("different-payload", investmentJson(assetName = "BOND")))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("idempotency_key_reused"))
            .andExpect(jsonPath("$.message").value("Idempotency key was already used for a different request."))
            .andExpect(jsonPath("$.fieldErrors").isEmpty)
            .andExpect(jsonPath("$.journalId").doesNotExist())

        assertThat(count("journals")).isEqualTo(1)
        assertThat(count("journal_idempotency_records")).isEqualTo(1)
        assertThat(jdbcTemplate.queryForObject("SELECT id FROM journals", String::class.java)).isNotNull
        assertThat(first.response.getHeader(HttpHeaders.LOCATION)).isEqualTo("/finance/journals/$journalId")
    }

    @Test
    fun sameKeyInvestmentThenStudyReturnsConflictBecauseTypeIsFingerprinted() {
        mockMvc.perform(createRequest("cross-type", investmentJson()))
            .andExpect(status().isCreated)

        mockMvc.perform(createRequest("cross-type", studyJson()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("idempotency_key_reused"))

        assertThat(count("journals")).isEqualTo(1)
        assertThat(count("investment_journals")).isEqualTo(1)
        assertThat(count("study_journals")).isZero
    }

    @Test
    fun invalidHeaderBoundariesReturnSafe400WithoutDatabaseMutation() {
        val cases = listOf(
            null to "missing",
            "  " to "blank",
            "a,b" to "comma joined",
            "-leading" to "invalid character",
            "a".repeat(129) to "too long",
        )

        cases.forEach { (key, _) ->
            val request = createRequest(key = key, body = investmentJson())
            val result = mockMvc.perform(request)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.fieldErrors").isArray)
                .andReturn()
            assertThat(result.response.contentAsString).doesNotContain("journalId")
        }

        mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "same", "same")
                .content(investmentJson()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("invalid_request"))

        assertThat(count("journals")).isZero
        assertThat(count("journal_idempotency_records")).isZero
    }

    @Test
    fun normalizedEquivalentPayloadReplaysTheSameJournal() {
        val first = mockMvc.perform(
            createRequest(
                key = "normalized-payload",
                body = investmentJson(assetName = "  ETF  ", reasoning = "  line one\nline  two  "),
            ),
        )
            .andExpect(status().isCreated)
            .andReturn()
        val journalId = first.response.getHeader(HttpHeaders.LOCATION).orEmpty().substringAfterLast('/')

        mockMvc.perform(
            createRequest(
                key = "normalized-payload",
                body = investmentJson(assetName = "ETF", reasoning = "line one\nline  two"),
            ),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.LOCATION, "/finance/journals/$journalId"))

        assertThat(count("journals")).isEqualTo(1)
    }

    private fun createRequest(key: String?, body: String) = post("/finance/journals")
        .contentType(MediaType.APPLICATION_JSON)
        .apply { if (key != null) header("Idempotency-Key", key) }
        .content(body)

    private fun count(table: String): Int = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM $table",
        Int::class.java,
    ) ?: error("Missing count for $table")

    private fun investmentJson(assetName: String = "ETF", reasoning: String = "thesis"): String = """
        {
          "type": "investment",
          "assetName": "${assetName.replace("\n", "\\n")}",
          "occurredAt": "2026-08-12T14:30:15.123",
          "timeZone": "Asia/Seoul",
          "action": "buy",
          "reasoning": "${reasoning.replace("\n", "\\n")}",
          "emotion": "확신"
        }
    """.trimIndent()

    private fun studyJson(): String = """
        {
          "type": "study",
          "title": "Study",
          "occurredAt": "2026-08-12T14:30",
          "timeZone": "Asia/Seoul",
          "keyContent": "content",
          "openQuestions": ["first", "same"]
        }
    """.trimIndent()
}
