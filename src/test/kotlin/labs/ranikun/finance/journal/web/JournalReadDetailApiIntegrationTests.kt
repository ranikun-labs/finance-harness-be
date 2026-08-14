package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class JournalReadDetailApiIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jsonMapper: JsonMapper

    @BeforeEach
    fun clearJournalRows() {
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
    }

    @Test
    fun investmentDetailRestoresPersistedTimeAndNullableEmotion() {
        val journalId = createInvestmentWithoutEmotion()

        val result = mockMvc.perform(get("/finance/journals/{journalId}", journalId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.journalId").value(journalId))
            .andExpect(jsonPath("$.type").value("investment"))
            .andExpect(jsonPath("$.occurredAt").value("2026-08-12T14:30:15.123"))
            .andExpect(jsonPath("$.timeZone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.assetName").value("ETF"))
            .andExpect(jsonPath("$.action").value("buy"))
            .andExpect(jsonPath("$.reasoning").value("thesis"))
            .andExpect(jsonPath("$.emotion").value(null))
            .andReturn()

        val json = jsonMapper.readTree(result.response.contentAsString)
        assertThat(json.get("createdAt").stringValue()).endsWith("Z")
        assertThat(json.get("updatedAt").stringValue()).endsWith("Z")
        assertThat(json.has("openQuestions")).isFalse()
    }

    @Test
    fun studyDetailPreservesOrderedDuplicateOpenQuestions() {
        val journalId = createStudy(listOf("first", "same", "same", "last"))

        val result = mockMvc.perform(get("/finance/journals/{journalId}", journalId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.journalId").value(journalId))
            .andExpect(jsonPath("$.type").value("study"))
            .andExpect(jsonPath("$.occurredAt").value("2026-08-12T14:30:00.000"))
            .andExpect(jsonPath("$.timeZone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.title").value("Study"))
            .andExpect(jsonPath("$.keyContent").value("content"))
            .andExpect(jsonPath("$.openQuestions[0]").value("first"))
            .andExpect(jsonPath("$.openQuestions[1]").value("same"))
            .andExpect(jsonPath("$.openQuestions[2]").value("same"))
            .andExpect(jsonPath("$.openQuestions[3]").value("last"))
            .andExpect(jsonPath("$.openQuestions[4]").doesNotExist())
            .andReturn()

        assertThat(result.response.contentAsString).contains("\"openQuestions\"")
    }

    @Test
    fun foreignAndNonexistentJournalUseTheSameNotFoundSemantics() {
        val foreignJournalId = createInvestmentWithoutEmotion()
        jdbcTemplate.update(
            "UPDATE journals SET identity_user_id = ? WHERE id = ?",
            "another-user",
            UUID.fromString(foreignJournalId),
        )

        val foreign = mockMvc.perform(get("/finance/journals/{journalId}", foreignJournalId))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("journal_not_found"))
            .andExpect(jsonPath("$.message").value("Journal not found."))
            .andExpect(jsonPath("$.fieldErrors").isEmpty)
            .andReturn()

        val nonexistent = mockMvc.perform(
            get("/finance/journals/{journalId}", "00000000-0000-4000-8000-000000000001"),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("journal_not_found"))
            .andExpect(jsonPath("$.message").value("Journal not found."))
            .andExpect(jsonPath("$.fieldErrors").isEmpty)
            .andReturn()

        val foreignJson = jsonMapper.readTree(foreign.response.contentAsString)
        val nonexistentJson = jsonMapper.readTree(nonexistent.response.contentAsString)
        assertThat(foreignJson.get("code").stringValue()).isEqualTo(nonexistentJson.get("code").stringValue())
        assertThat(foreignJson.get("message").stringValue()).isEqualTo(nonexistentJson.get("message").stringValue())
    }

    @Test
    fun malformedJournalIdIsAnInvalidRequest() {
        mockMvc.perform(get("/finance/journals/{journalId}", "not-a-uuid"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("invalid_request"))
            .andExpect(jsonPath("$.message").value("Request is invalid."))
    }

    private fun createInvestmentWithoutEmotion(): String {
        val result = mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "type": "investment",
                      "assetName": "ETF",
                      "occurredAt": "2026-08-12T14:30:15.123",
                      "timeZone": "Asia/Seoul",
                      "action": "buy",
                      "reasoning": "thesis"
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn()

        return result.response.getHeader("Location").orEmpty().substringAfterLast('/')
    }

    private fun createStudy(openQuestions: List<String>): String {
        val result = mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "type": "study",
                      "title": "Study",
                      "keyContent": "content",
                      "occurredAt": "2026-08-12T14:30",
                      "timeZone": "Asia/Seoul",
                      "openQuestions": [${openQuestions.joinToString(",") { "\"$it\"" }}]
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn()

        return result.response.getHeader("Location").orEmpty().substringAfterLast('/')
    }
}
