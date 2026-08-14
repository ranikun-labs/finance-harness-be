package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.nio.charset.StandardCharsets.UTF_8
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class JournalReadListApiIntegrationTests {

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
    fun emptyListReturnsItemsAndNullNextCursorWithoutTotalCount() {
        val result = mockMvc.perform(get("/finance/journals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.nextCursor").value(null))
            .andReturn()

        val json = jsonMapper.readTree(result.response.contentAsString)
        assertThat(json.has("totalCount")).isFalse()
        assertThat(json.has("nextCursor")).isTrue()
        assertThat(json.get("nextCursor").isNull).isTrue()
    }

    @Test
    fun listReturnsMixedTypeSummariesInOccurredAtDescendingOrder() {
        insertInvestment(
            id = uuid("00000000-0000-7000-8000-000000000001"),
            occurredAtUtc = Instant.parse("2026-08-12T06:00:00Z"),
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 15, 0),
            assetName = "ETF",
        )
        insertStudy(
            id = uuid("00000000-0000-7000-8000-000000000002"),
            occurredAtUtc = Instant.parse("2026-08-12T05:00:00Z"),
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 0),
            title = "Study",
        )

        val result = mockMvc.perform(get("/finance/journals").param("limit", "10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items").isArray)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].journalId").value("00000000-0000-7000-8000-000000000001"))
            .andExpect(jsonPath("$.items[0].type").value("investment"))
            .andExpect(jsonPath("$.items[0].assetName").value("ETF"))
            .andExpect(jsonPath("$.items[0].action").value("buy"))
            .andExpect(jsonPath("$.items[0].occurredAt").value("2026-08-12T15:00:00.000"))
            .andExpect(jsonPath("$.items[0].timeZone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.items[0].reasoning").doesNotExist())
            .andExpect(jsonPath("$.items[1].journalId").value("00000000-0000-7000-8000-000000000002"))
            .andExpect(jsonPath("$.items[1].type").value("study"))
            .andExpect(jsonPath("$.items[1].title").value("Study"))
            .andExpect(jsonPath("$.items[1].occurredAt").value("2026-08-12T14:00:00.000"))
            .andExpect(jsonPath("$.items[1].timeZone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.items[1].keyContent").doesNotExist())
            .andExpect(jsonPath("$.items[1].openQuestions").doesNotExist())
            .andExpect(jsonPath("$.nextCursor").value(null))
            .andReturn()

        assertThat(result.response.contentAsString).doesNotContain("reasoning")
    }

    @Test
    fun equalOccurredAtUsesJournalIdDescendingAsTieBreaker() {
        val occurredAt = Instant.parse("2026-08-12T05:00:00Z")
        insertInvestment(
            id = uuid("00000000-0000-7000-8000-000000000001"),
            occurredAtUtc = occurredAt,
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 0),
            assetName = "LOW",
        )
        insertInvestment(
            id = uuid("00000000-0000-7000-8000-000000000002"),
            occurredAtUtc = occurredAt,
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 0),
            assetName = "HIGH",
        )

        mockMvc.perform(get("/finance/journals").param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].journalId").value("00000000-0000-7000-8000-000000000002"))
            .andExpect(jsonPath("$.items[0].assetName").value("HIGH"))
            .andExpect(jsonPath("$.items[1].journalId").value("00000000-0000-7000-8000-000000000001"))
            .andExpect(jsonPath("$.items[1].assetName").value("LOW"))
            .andExpect(jsonPath("$.nextCursor").value(null))
    }

    @Test
    fun limitPlusOneProducesOpaqueCursorAndNextPageUsesKeysetBoundary() {
        insertInvestment(
            id = uuid("00000000-0000-7000-8000-000000000001"),
            occurredAtUtc = Instant.parse("2026-08-12T07:00:00Z"),
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 16, 0),
            assetName = "first",
        )
        insertInvestment(
            id = uuid("00000000-0000-7000-8000-000000000002"),
            occurredAtUtc = Instant.parse("2026-08-12T06:00:00Z"),
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 15, 0),
            assetName = "second",
        )
        insertInvestment(
            id = uuid("00000000-0000-7000-8000-000000000003"),
            occurredAtUtc = Instant.parse("2026-08-12T05:00:00Z"),
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 14, 0),
            assetName = "third",
        )

        val firstPage = mockMvc.perform(get("/finance/journals").param("limit", "2"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.items[0].assetName").value("first"))
            .andExpect(jsonPath("$.items[1].assetName").value("second"))
            .andExpect(jsonPath("$.nextCursor").isString)
            .andReturn()
        val cursor = jsonMapper.readTree(firstPage.response.contentAsString).get("nextCursor").stringValue()
        assertThat(cursor).isNotBlank().doesNotContain("=")
        assertThat(Base64.getUrlDecoder().decode(cursor).toString(UTF_8)).isEqualTo(
            """{"version":1,"occurredAtUtc":"2026-08-12T06:00:00Z","journalId":"00000000-0000-7000-8000-000000000002"}""",
        )

        mockMvc.perform(get("/finance/journals").param("limit", "2").param("cursor", cursor))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].assetName").value("third"))
            .andExpect(jsonPath("$.nextCursor").value(null))
    }

    @Test
    fun listIsolatedToCurrentOwnerEvenWhenForeignRowsExist() {
        insertInvestment(
            id = uuid("00000000-0000-7000-8000-000000000001"),
            occurredAtUtc = Instant.parse("2026-08-12T06:00:00Z"),
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 15, 0),
            assetName = "owned",
        )
        insertInvestment(
            id = uuid("00000000-0000-7000-8000-000000000002"),
            owner = "another-user",
            occurredAtUtc = Instant.parse("2026-08-12T07:00:00Z"),
            occurredLocalAt = LocalDateTime.of(2026, 8, 12, 16, 0),
            assetName = "foreign",
        )

        mockMvc.perform(get("/finance/journals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].assetName").value("owned"))
    }

    @Test
    fun omittedLimitUsesTwentyItemsAndLimitPlusOneCursorDetection() {
        (1..21).forEach { index ->
            insertInvestment(
                id = uuid("00000000-0000-7000-8000-%012d".format(index)),
                occurredAtUtc = Instant.parse("2026-08-12T00:00:00Z").plusSeconds(index.toLong()),
                occurredLocalAt = LocalDateTime.of(2026, 8, 12, 9, 0).plusSeconds(index.toLong()),
                assetName = "asset-$index",
            )
        }

        mockMvc.perform(get("/finance/journals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items.length()").value(20))
            .andExpect(jsonPath("$.nextCursor").isString)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0", "-1", "101", "not-a-number", ""])
    fun invalidLimitIsAnInvalidRequest(rawLimit: String) {
        mockMvc.perform(get("/finance/journals").param("limit", rawLimit))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("invalid_request"))
    }

    @Test
    fun duplicateLimitIsAnInvalidRequest() {
        mockMvc.perform(get("/finance/journals").param("limit", "1", "2"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("invalid_request"))
    }

    @Test
    fun invalidCursorIsAnInvalidRequest() {
        mockMvc.perform(get("/finance/journals").param("cursor", "not-a-cursor"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("invalid_request"))
    }

    @Test
    fun minimumAndMaximumLimitsAreAccepted() {
        mockMvc.perform(get("/finance/journals").param("limit", "1"))
            .andExpect(status().isOk)
        mockMvc.perform(get("/finance/journals").param("limit", "100"))
            .andExpect(status().isOk)
    }

    private fun insertInvestment(
        id: UUID,
        occurredAtUtc: Instant,
        occurredLocalAt: LocalDateTime,
        assetName: String,
        owner: String = "test-user-rpl-50",
    ) {
        insertBase(id, owner, "investment", occurredAtUtc, occurredLocalAt)
        jdbcTemplate.update(
            """
            INSERT INTO investment_journals (journal_id, journal_type, asset_name, action, reasoning, emotion)
            VALUES (?, 'investment', ?, 'buy', 'long reasoning', NULL)
            """.trimIndent(),
            id,
            assetName,
        )
    }

    private fun insertStudy(
        id: UUID,
        occurredAtUtc: Instant,
        occurredLocalAt: LocalDateTime,
        title: String,
        owner: String = "test-user-rpl-50",
    ) {
        insertBase(id, owner, "study", occurredAtUtc, occurredLocalAt)
        jdbcTemplate.update(
            """
            INSERT INTO study_journals (journal_id, journal_type, title, key_content)
            VALUES (?, 'study', ?, 'long key content')
            """.trimIndent(),
            id,
            title,
        )
    }

    private fun insertBase(
        id: UUID,
        owner: String,
        type: String,
        occurredAtUtc: Instant,
        occurredLocalAt: LocalDateTime,
    ) {
        val createdAt = Timestamp.from(Instant.parse("2026-08-13T00:00:00Z"))
        jdbcTemplate.update(
            """
            INSERT INTO journals (
                id, identity_user_id, type, occurred_at, occurred_local_at,
                occurred_time_zone, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, 'Asia/Seoul', ?, ?)
            """.trimIndent(),
            id,
            owner,
            type,
            Timestamp.from(occurredAtUtc),
            Timestamp.valueOf(occurredLocalAt),
            createdAt,
            createdAt,
        )
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)
}
