package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.json.JsonMapper
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import java.util.stream.Stream

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class JournalCreateApiIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var jsonMapper: JsonMapper

    private var idempotencyKeySequence = 0

    @Test
    fun scalarCoercionIsDisabled() {
        assertThat(jsonMapper.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS)).isFalse()
    }

    @BeforeEach
    fun clearJournalRows() {
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
        idempotencyKeySequence = 0
    }

    @Test
    fun investmentCreateReturns201LocationUuidV7AndPersistsConfiguredOwner() {
        val result = mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", nextIdempotencyKey())
                .content(investmentJson()),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/finance/journals/")))
            .andExpect(jsonPath("$.journalId").isNotEmpty)
            .andReturn()

        val journalId = result.response.contentAsString
            .substringAfter(":\"", "")
            .substringBefore("\"")
        val locationId = result.response.getHeader(HttpHeaders.LOCATION).orEmpty().substringAfterLast('/')

        assertThat(journalId).isNotBlank().isEqualTo(locationId)
        assertThat(UUID.fromString(journalId).version()).isEqualTo(7)
        assertThat(jdbcTemplate.queryForObject(
            "SELECT identity_user_id FROM journals WHERE id = ?",
            String::class.java,
            UUID.fromString(journalId),
        )).isEqualTo("test-user-rpl-50")
    }

    @Test
    fun studyCreateReturns201AndPersistsOrderedDuplicateQuestions() {
        val questions = listOf("first", "same", "same", "last")
        val result = mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", nextIdempotencyKey())
                .content(studyJson(questions)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/finance/journals/")))
            .andExpect(jsonPath("$.journalId").isNotEmpty)
            .andReturn()
        val journalId = result.response.getHeader(HttpHeaders.LOCATION).orEmpty().substringAfterLast('/')

        val persistedQuestions = jdbcTemplate.query(
            "SELECT position, question FROM study_open_questions WHERE study_journal_id = ? ORDER BY position",
            { row, _ -> row.getInt("position") to row.getString("question") },
            UUID.fromString(journalId),
        )

        assertThat(persistedQuestions).containsExactly(
            0 to "first",
            1 to "same",
            2 to "same",
            3 to "last",
        )
    }

    @Test
    fun omittedEmotionIsAcceptedAndPersistsAsNull() {
        val result = mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", nextIdempotencyKey())
                .content(investmentJsonWithoutEmotion()),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.journalId").isNotEmpty)
            .andReturn()
        val journalId = result.response.getHeader(HttpHeaders.LOCATION).orEmpty().substringAfterLast('/')

        assertThat(jdbcTemplate.queryForObject(
            "SELECT emotion FROM investment_journals WHERE journal_id = ?",
            String::class.java,
            UUID.fromString(journalId),
        )).isNull()
    }

    @Test
    fun trimsOuterWhitespaceAndPreservesInternalFormatting() {
        val result = mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", nextIdempotencyKey())
                .content(
                    investmentJson()
                        .replace("\"assetName\": \"ETF\"", "\"assetName\": \"  ETF  \"")
                        .replace("\"reasoning\": \"thesis\"", "\"reasoning\": \"  line one\\nline  two  \""),
                ),
        )
            .andExpect(status().isCreated)
            .andReturn()
        val journalId = result.response.getHeader(HttpHeaders.LOCATION).orEmpty().substringAfterLast('/')

        assertThat(jdbcTemplate.queryForMap(
            "SELECT asset_name, reasoning FROM investment_journals WHERE journal_id = ?",
            UUID.fromString(journalId),
        )).containsEntry("asset_name", "ETF")
            .containsEntry("reasoning", "line one\nline  two")
    }

    @Test
    fun emptyQuestionsAndTenQuestionsWithFiveHundredCharacterItemAreValid() {
        performValidStudy(emptyList())
        performValidStudy(List(10) { if (it == 0) "q".repeat(500) else "q$it" })
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPayloads")
    fun invalidJsonAndValidationCasesReturnSafe400(name: String, payload: String) {
        mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", nextIdempotencyKey())
                .content(payload),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("invalid_request"))
            .andExpect(jsonPath("$.message").value("Request is invalid."))
            .andExpect(jsonPath("$.requestId").isNotEmpty)
            .andExpect(jsonPath("$.fieldErrors").isArray)

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journals", Int::class.java)).isZero
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM journal_idempotency_records",
            Int::class.java,
        )).isZero
    }

    private fun performValidStudy(questions: List<String>) {
        mockMvc.perform(
            post("/finance/journals")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", nextIdempotencyKey())
                .content(studyJson(questions)),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string(HttpHeaders.LOCATION, org.hamcrest.Matchers.startsWith("/finance/journals/")))
            .andExpect(jsonPath("$.journalId").isNotEmpty)
    }

    private fun nextIdempotencyKey(): String = "rpl50-api-${++idempotencyKeySequence}"

    companion object {
        private fun investmentJson(extraFields: String = ""): String = """
            {
              "type": "investment",
              "assetName": "ETF",
              "occurredAt": "2026-08-12T14:30:15.123",
              "timeZone": "Asia/Seoul",
              "action": "buy",
              "reasoning": "thesis",
              $extraFields
              "emotion": "확신"
            }
        """.trimIndent()

        private fun studyJson(questions: List<String>): String = """
            {
              "type": "study",
              "title": "Study",
              "occurredAt": "2026-08-12T14:30",
              "timeZone": "Asia/Seoul",
              "keyContent": "content",
              "openQuestions": [${questions.joinToString(",") { "\"$it\"" }}]
            }
        """.trimIndent()

        @JvmStatic
        fun invalidPayloads(): Stream<Arguments> = Stream.of(
            Arguments.of("malformed JSON", "{"),
            Arguments.of("unknown property", investmentJsonWith("\"unexpected\":true,")),
            Arguments.of("unknown type", investmentJson().replace("\"type\": \"investment\"", "\"type\": \"other\"")),
            Arguments.of("missing required field", investmentJson().replace("\"assetName\": \"ETF\",", "")),
            Arguments.of("explicit null required field", investmentJson().replace("\"assetName\": \"ETF\"", "\"assetName\": null")),
            Arguments.of("blank required field", investmentJson().replace("\"assetName\": \"ETF\"", "\"assetName\": \"  \"")),
            Arguments.of("unknown action", investmentJson().replace("\"action\": \"buy\"", "\"action\": \"hold\"")),
            Arguments.of("unknown emotion", investmentJson().replace("\"emotion\": \"확신\"", "\"emotion\": \"joy\"")),
            Arguments.of("explicit null optional emotion", investmentJson().replace("\"emotion\": \"확신\"", "\"emotion\": null")),
            Arguments.of("invalid field type", investmentJson().replace("\"assetName\": \"ETF\"", "\"assetName\": 42")),
            Arguments.of("asset name too long", investmentJson().replace("\"assetName\": \"ETF\"", "\"assetName\": \"${"a".repeat(121)}\"")),
            Arguments.of("reasoning too long", investmentJson().replace("\"reasoning\": \"thesis\"", "\"reasoning\": \"${"r".repeat(4001)}\"")),
            Arguments.of("study title too long", studyJson(emptyList()).replace("\"title\": \"Study\"", "\"title\": \"${"t".repeat(121)}\"")),
            Arguments.of("study key content too long", studyJson(emptyList()).replace("\"keyContent\": \"content\"", "\"keyContent\": \"${"k".repeat(6001)}\"")),
            Arguments.of("cross subtype property", investmentJsonWith("\"title\":\"wrong subtype\",")),
            Arguments.of("userId spoof", investmentJsonWith("\"userId\":\"spoof\",")),
            Arguments.of("identityUserId spoof", investmentJsonWith("\"identityUserId\":\"spoof\",")),
            Arguments.of("actorId spoof", investmentJsonWith("\"actorId\":\"spoof\",")),
            Arguments.of("missing openQuestions", studyJson(listOf()).replace("\"openQuestions\": []", "")),
            Arguments.of("null openQuestions", studyJson(listOf()).replace("\"openQuestions\": []", "\"openQuestions\": null")),
            Arguments.of("null question item", studyJson(listOf("first")).replace("[\"first\"]", "[null]")),
            Arguments.of("blank question item", studyJson(listOf("first")).replace("[\"first\"]", "[\"  \"]")),
            Arguments.of("eleven questions", studyJson(List(11) { "q$it" })),
            Arguments.of("five hundred one character question", studyJson(listOf("q".repeat(501)))),
            Arguments.of("invalid local datetime", investmentJson().replace("2026-08-12T14:30:15.123", "2026-02-30T14:30")),
            Arguments.of("offset local datetime", investmentJson().replace("2026-08-12T14:30:15.123", "2026-08-12T14:30+09:00")),
            Arguments.of("invalid IANA zone", investmentJson().replace("Asia/Seoul", "Not/AZone")),
            Arguments.of("DST gap", investmentJson().replace("2026-08-12T14:30:15.123", "2026-03-08T02:30").replace("Asia/Seoul", "America/New_York")),
            Arguments.of("DST overlap", investmentJson().replace("2026-08-12T14:30:15.123", "2026-11-01T01:30").replace("Asia/Seoul", "America/New_York")),
        )

        private fun investmentJsonWith(extraFields: String): String = """
            {
              "type": "investment",
              $extraFields
              "assetName": "ETF",
              "occurredAt": "2026-08-12T14:30:15.123",
              "timeZone": "Asia/Seoul",
              "action": "buy",
              "reasoning": "thesis",
              "emotion": "확신"
            }
        """.trimIndent()

        private fun investmentJsonWithoutEmotion(): String = investmentJson()
            .replace(Regex(",\\s*\"emotion\": \"확신\""), "")
    }
}
