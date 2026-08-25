package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.journal.application.JournalCreateApplicationService
import labs.ranikun.finance.journal.application.JournalCreateCommand
import labs.ranikun.finance.journal.application.JournalReadApplicationService
import labs.ranikun.finance.journal.application.JournalReadStore
import labs.ranikun.finance.journal.application.JournalCursor
import labs.ranikun.finance.journal.application.JournalDetailRecord
import labs.ranikun.finance.journal.application.JournalListRecord
import labs.ranikun.finance.journal.application.ResolvedJournalTime
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.journal.domain.JournalAction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mockingDetails
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class JournalReadQueryCountIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var journalCreateApplicationService: JournalCreateApplicationService

    @MockitoSpyBean
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearJournalRows() {
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
        clearInvocations(jdbcTemplate)
    }

    @Test
    fun investmentDetailUsesOneBusinessQuery() {
        val journalId = createInvestment()
        clearInvocations(jdbcTemplate)

        mockMvc.perform(get("/finance/journals/{journalId}", journalId))
            .andExpect(status().isOk)

        assertThat(jdbcQueryInvocationCount()).isEqualTo(1)
    }

    @Test
    fun studyDetailUsesOneProjectionAndOneQuestionQuery() {
        val journalId = createStudy()
        clearInvocations(jdbcTemplate)

        mockMvc.perform(get("/finance/journals/{journalId}", journalId))
            .andExpect(status().isOk)

        assertThat(jdbcQueryInvocationCount()).isEqualTo(2)
    }

    @Test
    fun listUsesOneProjectionQueryPerPage() {
        createInvestment()
        clearInvocations(jdbcTemplate)

        mockMvc.perform(get("/finance/journals").param("limit", "1"))
            .andExpect(status().isOk)

        assertThat(jdbcQueryInvocationCount()).isEqualTo(1)
    }

    private fun jdbcQueryInvocationCount(): Int = mockingDetails(jdbcTemplate).invocations
        .count { invocation ->
            invocation.method.name == "query" &&
                invocation.method.parameterTypes.firstOrNull() == String::class.java &&
                invocation.method.parameterTypes.getOrNull(1) == RowMapper::class.java
        }

    private fun createInvestment(): String {
        val journalId = journalCreateApplicationService.create(
            JournalCreateCommand.Investment(
                assetName = "ETF",
                action = JournalAction.BUY,
                reasoning = "thesis",
                emotion = null,
                occurredAt = ResolvedJournalTime(
                    occurredAt = Instant.parse("2026-08-12T05:30:15.123Z"),
                    occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 30, 15, 123_000_000),
                    occurredTimeZone = "Asia/Seoul",
                ),
            ),
            idempotencyKey = "query-count-investment",
        )
        return journalId.toString()
    }

    private fun createStudy(): String {
        val journalId = journalCreateApplicationService.create(
            JournalCreateCommand.Study(
                title = "Study",
                keyContent = "content",
                openQuestions = listOf("first", "second"),
                occurredAt = ResolvedJournalTime(
                    occurredAt = Instant.parse("2026-08-12T05:30:15.123Z"),
                    occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 30, 15, 123_000_000),
                    occurredTimeZone = "Asia/Seoul",
                ),
            ),
            idempotencyKey = "query-count-study",
        )
        return journalId.toString()
    }
}

class JournalCursorCodecTests {

    private val codec = JournalCursorCodec(tools.jackson.databind.json.JsonMapper.builder().build())

    @Test
    fun encodesTheFrozenCursorShapeWithoutPadding() {
        val cursor = JournalCursor(
            version = 1,
            occurredAtUtc = Instant.parse("2026-08-12T06:00:00Z"),
            journalId = UUID.fromString("00000000-0000-7000-8000-000000000002"),
        )

        val encoded = codec.encode(cursor)

        assertThat(encoded).doesNotContain("=")
        assertThat(
            java.util.Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8),
        ).isEqualTo(
            """{"version":1,"occurredAtUtc":"2026-08-12T06:00:00Z","journalId":"00000000-0000-7000-8000-000000000002"}""",
        )
    }

    @Test
    fun rejectsNonCanonicalCursorShapesAndVersions() {
        val invalidPayloads = listOf(
            """{"version":2,"occurredAtUtc":"2026-08-12T06:00:00Z","journalId":"00000000-0000-7000-8000-000000000002"}""",
            """{"version":1,"occurredAtUtc":"2026-08-12T06:00:00+00:00","journalId":"00000000-0000-7000-8000-000000000002"}""",
            """{"version":1,"occurredAtUtc":"2026-08-12T06:00:00Z","journalId":"not-a-uuid"}""",
            """{"version":1,"occurredAtUtc":"2026-08-12T06:00:00Z"}""",
            """{"version":1,"occurredAtUtc":"2026-08-12T06:00:00Z","journalId":"00000000-0000-7000-8000-000000000002","extra":true}""",
            """{ "version":1,"occurredAtUtc":"2026-08-12T06:00:00Z","journalId":"00000000-0000-7000-8000-000000000002"}""",
        )

        invalidPayloads.forEach { payload ->
            assertThatThrownBy { codec.decode(encodePayload(payload)) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun rejectsPaddedAndMalformedBase64() {
        val valid = JournalCursor(
            version = 1,
            occurredAtUtc = Instant.parse("2026-08-12T06:00:00Z"),
            journalId = UUID.fromString("00000000-0000-7000-8000-000000000002"),
        )

        assertThatThrownBy { codec.decode(codec.encode(valid) + "=") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { codec.decode("not-a-base64-cursor") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun encodePayload(payload: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
}

class JournalReadApplicationServiceTests {

    @Test
    fun everyReadUsesTheCurrentUserPortAtCallTime() {
        val currentUserPort = MutableCurrentUserPort("first-user")
        val store = RecordingJournalReadStore()
        val service = JournalReadApplicationService(currentUserPort, store)

        service.findList(limit = 20, cursor = null)
        currentUserPort.current = IdentityUserId("second-user")
        service.findList(limit = 20, cursor = null)

        assertThat(store.listOwners).containsExactly(
            IdentityUserId("first-user"),
            IdentityUserId("second-user"),
        )
    }

    private class MutableCurrentUserPort(initial: String) : CurrentUserPort {
        var current = IdentityUserId(initial)

        override fun currentUserId(): IdentityUserId = current
    }

    private class RecordingJournalReadStore : JournalReadStore {
        val listOwners = mutableListOf<IdentityUserId>()

        override fun findDetail(owner: IdentityUserId, journalId: UUID): JournalDetailRecord? = null

        override fun findStudyOpenQuestions(owner: IdentityUserId, journalId: UUID): List<String> = emptyList()

        override fun findList(owner: IdentityUserId, cursor: JournalCursor?, limitPlusOne: Int): List<JournalListRecord> {
            listOwners += owner
            return emptyList()
        }
    }
}
