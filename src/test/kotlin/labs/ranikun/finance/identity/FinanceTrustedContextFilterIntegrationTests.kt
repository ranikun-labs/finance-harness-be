package labs.ranikun.finance.identity

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

@AutoConfigureMockMvc
@SpringBootTest
@org.springframework.context.annotation.Import(TestcontainersConfiguration::class)
@ActiveProfiles("test", "finance-trusted-context-reference")
class FinanceTrustedContextFilterIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var currentUserPort: CurrentUserPort

    @Autowired
    lateinit var environment: Environment

    private val fixtureSecret: String
        get() = environment.getRequiredProperty("finance.auth.trusted-context.service-secret")

    @BeforeEach
    fun clearRows() {
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
    }

    @Test
    fun missingCallerEnvelopeIsGenericForbiddenAndDoesNotCreateRows() {
        mockMvc.perform(post("/finance/journals")
            .contentType(MediaType.APPLICATION_JSON)
            .content(CREATE_BODY))
            .andExpect(status().isForbidden)
            .andExpect(content().json("""{"code":"untrusted_finance_context"}"""))

        assertNoRows()
    }

    @Test
    fun rejectsDuplicateUnknownLegacyAndMalformedSubjectHeadersWithoutPersistence() {
        val subject = encodedSubject("user-a")
        val requests = listOf(
            validPost(subject).header(FinanceTrustedContextFilter.HEADER_SUBJECT, subject, subject),
            validPost(subject).header("X-Finance-Extra", "unexpected"),
            validPost(subject).header("X-User-Id", "other-user"),
            validPost("YWJj="),
            validPost("__4"), // canonical Base64URL, but invalid UTF-8
            validPost(encodedSubject("   ")),
            validPost(encodedSubject("x".repeat(257))),
            validPost(subject).header(FinanceTrustedContextFilter.HEADER_AUDIENCE, "finance-journal,finance-journal"),
            validPost(subject).header(FinanceTrustedContextFilter.HEADER_VERSION, "2"),
            validPost(subject).header(FinanceTrustedContextFilter.HEADER_SECRET, "wrong-public-dummy-value-that-is-not-the-configured-one"),
        )

        requests.forEach { request ->
            mockMvc.perform(request)
                .andExpect(status().isForbidden)
                .andExpect(content().json("""{"code":"untrusted_finance_context"}"""))
        }

        assertNoRows()
    }

    @Test
    fun unsupportedMethodsAndJournalPathsAreRejectedBeforeControllerDispatch() {
        val headers = headersFor("user-a")
        mockMvc.perform(get("/finance/journals/not-a-uuid").withHeaders(headers))
            .andExpect(status().isForbidden)
        mockMvc.perform(post("/finance/journals/not-a-uuid").withHeaders(headers))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/finance/journals/").withHeaders(headers))
            .andExpect(status().isForbidden)

        assertNoRows()
    }

    @Test
    fun requestIdentityIsClearedAfterRejectedAndCompletedRequests() {
        mockMvc.perform(get("/finance/journals"))
            .andExpect(status().isForbidden)
        assertThat(runCatching { currentUserPort.currentUserId() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)

        mockMvc.perform(get("/finance/journals").withHeaders(headersFor("cleanup-user")))
            .andExpect(status().isOk)
        assertThat(runCatching { currentUserPort.currentUserId() }.exceptionOrNull())
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun realGatewayVectorsCreateAndReplayIndependentOwnerJournals() {
        val vectors = loadVectorsOrSkip()
        assertThat(vectors.map { it.id }).containsExactly("A", "B")
        assertThat(vectors.map { it.expectedSubject }).doesNotHaveDuplicates()

        val a = vectors.single { it.id == "A" }
        val b = vectors.single { it.id == "B" }
        assertThat(a.headers[FinanceTrustedContextFilter.HEADER_SECRET]).isEqualTo(fixtureSecret)
        assertThat(b.headers[FinanceTrustedContextFilter.HEADER_SECRET]).isEqualTo(fixtureSecret)
        assertThat(a.expectedSubject).isEqualTo("9f8c1f5a-8a1a-4f9f-b9e3-2cc1d3b1a123")
        assertThat(b.expectedSubject).isEqualTo("4b7a6711-4a33-4892-9b8a-8730b83a1533")

        val firstA = mockMvc.perform(createWith(a.headers))
            .andExpect(status().isCreated)
            .andReturn()
        val firstB = mockMvc.perform(createWith(b.headers))
            .andExpect(status().isCreated)
            .andReturn()
        val replayA = mockMvc.perform(createWith(a.headers))
            .andExpect(status().isCreated)
            .andReturn()
        val replayB = mockMvc.perform(createWith(b.headers))
            .andExpect(status().isCreated)
            .andReturn()

        assertThat(firstA.response.getHeader("Location")).isNotEqualTo(firstB.response.getHeader("Location"))
        assertThat(replayA.response.getHeader("Location")).isEqualTo(firstA.response.getHeader("Location"))
        assertThat(replayB.response.getHeader("Location")).isEqualTo(firstB.response.getHeader("Location"))
        assertThat(replayA.response.contentAsString).isEqualTo(firstA.response.contentAsString)
        assertThat(replayB.response.contentAsString).isEqualTo(firstB.response.contentAsString)

        val aLocation = requireNotNull(firstA.response.getHeader("Location"))
        val bLocation = requireNotNull(firstB.response.getHeader("Location"))
        mockMvc.perform(get(aLocation).withHeaders(a.headers)).andExpect(status().isOk)
        mockMvc.perform(get(bLocation).withHeaders(b.headers)).andExpect(status().isOk)
        mockMvc.perform(get(aLocation).withHeaders(b.headers)).andExpect(status().isNotFound)
        mockMvc.perform(get(bLocation).withHeaders(a.headers)).andExpect(status().isNotFound)

        val aList = mockMvc.perform(get("/finance/journals").withHeaders(a.headers))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val bList = mockMvc.perform(get("/finance/journals").withHeaders(b.headers))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertThat(aList).contains(aLocation.substringAfterLast('/')).doesNotContain(bLocation.substringAfterLast('/'))
        assertThat(bList).contains(bLocation.substringAfterLast('/')).doesNotContain(aLocation.substringAfterLast('/'))

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journals", Int::class.java)).isEqualTo(2)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_idempotency_records", Int::class.java))
            .isEqualTo(2)
        assertThat(jdbcTemplate.queryForList("SELECT identity_user_id FROM journals ORDER BY identity_user_id", String::class.java))
            .containsExactlyInAnyOrder(a.expectedSubject, b.expectedSubject)
    }

    private fun validPost(subject: String): MockHttpServletRequestBuilder =
        createWith(headersForSubject(subject))

    private fun createWith(headers: Map<String, String>): MockHttpServletRequestBuilder =
        post("/finance/journals")
            .withHeaders(headers)
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", IDEMPOTENCY_KEY)
            .content(CREATE_BODY)

    private fun headersFor(subject: String): Map<String, String> = headersForSubject(encodedSubject(subject))

    private fun headersForSubject(subject: String): Map<String, String> = mapOf(
        FinanceTrustedContextFilter.HEADER_VERSION to FinanceTrustedContextFilter.CONTEXT_VERSION,
        FinanceTrustedContextFilter.HEADER_SERVICE_ID to FinanceTrustedContextFilter.SERVICE_ID,
        FinanceTrustedContextFilter.HEADER_SECRET to fixtureSecret,
        FinanceTrustedContextFilter.HEADER_AUDIENCE to FinanceTrustedContextFilter.AUDIENCE,
        FinanceTrustedContextFilter.HEADER_SUBJECT to subject,
    )

    private fun MockHttpServletRequestBuilder.withHeaders(headers: Map<String, String>) = apply {
        headers.forEach { (name, value) -> header(name, value) }
    }

    private fun loadVectorsOrSkip(): List<GatewayVector> {
        val pathValue = System.getenv(VECTORS_ENV)
        assumeTrue(!pathValue.isNullOrBlank(), "Gateway producer vector artifact is not available; coupled case NOT RUN")
        val root = JsonMapper.builder().build().readTree(Files.readString(Path.of(requireNotNull(pathValue))))
        assertThat(root["contract_sha256"].asString())
            .isEqualTo("3b4138cd0cc57fa56f8607be3fafc4255e5dc70f7c311beb424fa739545e6326")
        assertThat(root["producer_kind"].asString()).isEqualTo("REAL_PRODUCER_FIXTURE")
        val vectorNodes = root["vectors"]
        assertThat(vectorNodes.isArray).isTrue()
        return vectorNodes.toList().map { vectorNode ->
            val headersNode = vectorNode["headers"]
            val headers = headersNode.properties().associate { (name, value) -> name to value.asString() }
            assertThat(headers.keys).containsExactlyInAnyOrder(
                FinanceTrustedContextFilter.HEADER_VERSION,
                FinanceTrustedContextFilter.HEADER_SERVICE_ID,
                FinanceTrustedContextFilter.HEADER_SECRET,
                FinanceTrustedContextFilter.HEADER_AUDIENCE,
                FinanceTrustedContextFilter.HEADER_SUBJECT,
            )
            GatewayVector(
                id = vectorNode["id"].asString(),
                expectedSubject = vectorNode["expected_subject"].asString(),
                headers = headers,
            )
        }
    }

    private fun assertNoRows() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journals", Int::class.java)).isZero()
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journal_idempotency_records", Int::class.java)).isZero()
    }

    private fun encodedSubject(subject: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(subject.toByteArray(StandardCharsets.UTF_8))

    private data class GatewayVector(
        val id: String,
        val expectedSubject: String,
        val headers: Map<String, String>,
    )

    companion object {
        private const val VECTORS_ENV = "D1_FIXTURE_VECTORS_PATH"
        private const val IDEMPOTENCY_KEY = "same-key-produced-vector"
        private val CREATE_BODY = """
            {
              "type": "investment",
              "assetName": "ETF",
              "occurredAt": "2026-08-12T14:30:15.123",
              "timeZone": "Asia/Seoul",
              "action": "buy",
              "reasoning": "trusted context receiver fixture"
            }
        """.trimIndent()
    }
}
