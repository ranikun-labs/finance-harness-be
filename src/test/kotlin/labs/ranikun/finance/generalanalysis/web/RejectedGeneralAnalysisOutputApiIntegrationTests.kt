package labs.ranikun.finance.generalanalysis.web

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionInput
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionResult
import labs.ranikun.finance.generalanalysis.domain.GeneralAnalysisCandidate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

@TestConfiguration(proxyBeanMethods = false)
class RejectedOutputExecutionConfiguration {
    @Bean
    @Primary
    fun rejectedOutputExecutionPort(): GeneralAnalysisExecutionPort = object : GeneralAnalysisExecutionPort {
        override fun execute(input: GeneralAnalysisExecutionInput): GeneralAnalysisExecutionResult {
            val candidate = when {
                input.question.contains("malformed") -> GeneralAnalysisCandidate(
                    summary = "malformed candidate secret",
                    observations = emptyList(),
                    assumptions = listOf("No external data was supplied."),
                    nextChecks = listOf("Verify relevant facts with a primary source."),
                    evidenceNote = "Evidence: user-provided question only.",
                )

                else -> GeneralAnalysisCandidate(
                    summary = "prohibited candidate secret: you should buy now",
                    observations = listOf("Only the submitted question was provided as input."),
                    assumptions = listOf("No external data was supplied."),
                    nextChecks = listOf("Verify relevant facts with a primary source."),
                    evidenceNote = "Evidence: user-provided question only.",
                )
            }
            return GeneralAnalysisExecutionResult.Success(candidate)
        }
    }
}

@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, RejectedOutputExecutionConfiguration::class)
@SpringBootTest
@ActiveProfiles("test")
class RejectedGeneralAnalysisOutputApiIntegrationTests {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jsonMapper: JsonMapper

    @Test
    fun malformedCandidateIsFailedAndNeverReturnedByApiOrReadBack() {
        assertCandidateRejected("Explain malformed output fixture", "malformed candidate secret")
    }

    @Test
    fun prohibitedCandidateIsFailedAndNeverReturnedByApiOrReadBack() {
        assertCandidateRejected("Summarize prohibited output fixture", "prohibited candidate secret")
    }

    private fun assertCandidateRejected(questionText: String, forbiddenText: String) {
        val createdQuestion = mockMvc.perform(
            post("/finance/general-analysis/questions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsBytes(mapOf("question" to questionText))),
        )
            .andExpect(status().isCreated)
            .andReturn()
            .response
            .contentAsString
        val question = jsonMapper.readTree(createdQuestion)
        val questionId = question.path("questionId").asText()
        val checklistId = question.path("checklistId").asText()

        mockMvc.perform(
            put("/finance/general-analysis/questions/{questionId}/checklists/{checklistId}", questionId, checklistId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsBytes(mapOf("checkedItemIds" to emptyList<String>(), "confirmed" to true))),
        ).andExpect(status().isOk)

        val analysisBody = mockMvc.perform(
            post("/finance/general-analysis/questions/{questionId}/analysis", questionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsBytes(mapOf("checklistId" to checklistId))),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andReturn()
            .response
            .contentAsString

        assertThat(analysisBody).doesNotContain(forbiddenText)
        val analysisId = jsonMapper.readTree(analysisBody).path("analysisId").asText()
        val readBackBody = mockMvc.perform(get("/finance/general-analysis/analyses/{analysisId}", analysisId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andReturn()
            .response
            .contentAsString
        assertThat(readBackBody).doesNotContain(forbiddenText)
    }
}
