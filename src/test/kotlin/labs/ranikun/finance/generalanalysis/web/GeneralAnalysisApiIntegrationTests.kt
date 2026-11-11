package labs.ranikun.finance.generalanalysis.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import labs.ranikun.finance.TestcontainersConfiguration
import org.springframework.http.MediaType
import tools.jackson.databind.json.JsonMapper

@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ActiveProfiles("test")
class GeneralAnalysisApiIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jsonMapper: JsonMapper

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun successfulAnalysisDoesNotCreateJournal() {
        val journalCountBefore = jdbcTemplate.queryForObject("select count(*) from journals", Long::class.java)!!
        val questionResponse = mockMvc.perform(
            post("/finance/general-analysis/questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsBytes(mapOf("question" to "How do I check this information?"))),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        val question = jsonMapper.readTree(questionResponse)
        val questionId = question.path("questionId").asText()
        val checklistId = question.path("checklistId").asText()

        mockMvc.perform(
            put("/finance/general-analysis/questions/{questionId}/checklists/{checklistId}", questionId, checklistId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsBytes(mapOf("checkedItemIds" to listOf("known_facts"), "confirmed" to true))),
        ).andExpect(status().isOk)

        mockMvc.perform(
            post("/finance/general-analysis/questions/{questionId}/analysis", questionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsBytes(mapOf("checklistId" to checklistId))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.output.summary").isNotEmpty)

        val journalCountAfter = jdbcTemplate.queryForObject("select count(*) from journals", Long::class.java)!!
        assertThat(journalCountAfter).isEqualTo(journalCountBefore)

        mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "rpl127-explicit-journal-save")
                .content(
                    jsonMapper.writeValueAsBytes(
                        mapOf(
                            "type" to "investment",
                            "assetName" to "ETF",
                            "occurredAt" to "2026-08-12T14:30:15.123",
                            "timeZone" to "Asia/Seoul",
                            "action" to "watching",
                            "reasoning" to "Saved explicitly after analysis.",
                        ),
                    ),
                ),
        ).andExpect(status().isCreated)

        val journalCountAfterExplicitSave =
            jdbcTemplate.queryForObject("select count(*) from journals", Long::class.java)!!
        assertThat(journalCountAfterExplicitSave).isEqualTo(journalCountBefore + 1)
    }
}
