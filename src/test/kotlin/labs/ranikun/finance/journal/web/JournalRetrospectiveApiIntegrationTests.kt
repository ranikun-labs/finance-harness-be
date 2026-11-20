package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@AutoConfigureMockMvc
@SpringBootTest(properties = ["finance.journal.retrospectives.enabled=true"])
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test", "finance-trusted-context-reference")
class JournalRetrospectiveApiIntegrationTests {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var env: Environment
    @Autowired lateinit var dataSource: DataSource

    private val mapper = JsonMapper.builder().build()
    private val draft = """{"sourceRevision":0,"body":"Original judgment revisited","nextCheck":"Check facts"}"""

    @BeforeEach
    fun clean() {
        jdbc.update("DELETE FROM journal_idempotency_records")
        jdbc.update("DELETE FROM journals")
    }

    @Test
    fun savesSeparatelyAndReturnsTheSameRecordOnConcurrentReplay() {
        val journal = createJournal()
        val original = jdbc.queryForMap("SELECT revision, updated_at FROM journals")
        val workers = Executors.newFixedThreadPool(2)
        try {
            val responses = workers.invokeAll(listOf(Callable { append(journal) }, Callable { append(journal) }))
                .map { it.get() }
            assertThat(responses.map { it.status }).containsExactly(201, 201)
            val first = mapper.readTree(responses.first().contentAsString)
            assertThat(mapper.readTree(responses.last().contentAsString)).isEqualTo(first)
            val location = responses.first().getHeader("Location")!!
            val persisted = mvc.perform(trusted(get(location))).andReturn().response
            assertThat(persisted.status).isEqualTo(200)
            assertThat(mapper.readTree(persisted.contentAsString)).isEqualTo(first)
            val history = mapper.readTree(list(journal).contentAsString)
            assertThat(history.get("items").size()).isEqualTo(1)
            assertThat(history.get("items").get(0)).isEqualTo(first)
            assertThat(jdbc.queryForMap("SELECT revision, updated_at FROM journals")).isEqualTo(original)
            assertThat(append(journal, raw = draft.replace("Check facts", "Another check")).status)
                .isEqualTo(409)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun checksOwnerStateAndRevisionBeforeCreatingAndConcealsDeletedRecords() {
        val journal = createJournal()
        assertThat(list(journal).status).isEqualTo(200)
        assertThat(mapper.readTree(list(journal).contentAsString).get("items").size()).isZero()
        assertThat(list(journal, "owner-b").status).isEqualTo(404)
        assertThat(append(journal, "owner-b").status).isEqualTo(404)
        assertThat(mvc.perform(get(path(journal))).andReturn().response.status).isEqualTo(403)
        assertThat(append(journal, raw = draft.replace(":0", ":0.2")).status).isEqualTo(400)
        val created = append(journal)
        assertThat(created.status).isEqualTo(201)
        val location = created.getHeader("Location")!!
        assertThat(mvc.perform(trusted(get(location), "owner-b")).andReturn().response.status)
            .isEqualTo(404)
        jdbc.update("UPDATE journals SET revision = 1")
        val stale = append(journal, key = "new-key")
        assertThat(stale.status).isEqualTo(409)
        assertThat(mapper.readTree(stale.contentAsString).get("code").asText())
            .isEqualTo("journal_revision_conflict")
        assertThat(append(journal).contentAsString).isEqualTo(created.contentAsString)
        jdbc.update("UPDATE journals SET deleted_at = CURRENT_TIMESTAMP")
        assertThat(list(journal).status).isEqualTo(404)
        assertThat(append(journal).status).isEqualTo(404)
        assertThat(mvc.perform(trusted(get(location))).andReturn().response.status).isEqualTo(404)
        jdbc.update("DELETE FROM journal_idempotency_records")
        jdbc.update("DELETE FROM journals")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_retrospectives", Long::class.java))
            .isZero()
    }

    @Test
    fun migratesPopulatedV4WithoutChangingTheOriginalJournal() {
        val schema = "retro_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        val journalId = UUID.randomUUID()
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("4").load().migrate()
            jdbc.update(
                """
                INSERT INTO "$schema".journals
                  (id, identity_user_id, type, occurred_at, occurred_local_at, occurred_time_zone, created_at, updated_at)
                VALUES (?, 'upgrade-owner', 'study', now(), LOCALTIMESTAMP, 'UTC', now(), now())
                """.trimIndent(),
                journalId,
            )
            jdbc.update(
                """INSERT INTO "$schema".study_journals (journal_id, journal_type, title, key_content)
                   VALUES (?, 'study', 'Original note', 'Original text')""",
                journalId,
            )
            val before = jdbc.queryForMap("""SELECT * FROM "$schema".journals WHERE id = ?""", journalId)
            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate()
            val after = jdbc.queryForMap("""SELECT * FROM "$schema".journals WHERE id = ?""", journalId)
            assertThat(after).isEqualTo(before)
            assertThat(jdbc.queryForObject("""SELECT count(*) FROM "$schema".journal_retrospectives""", Long::class.java))
                .isZero()
        } finally {
            jdbc.execute("""DROP SCHEMA IF EXISTS "$schema" CASCADE""")
        }
    }

    private fun path(journal: String) = "/finance/journals/$journal/retrospectives"

    private fun list(journal: String, owner: String = "owner-a") =
        mvc.perform(trusted(get(path(journal)), owner)).andReturn().response

    private fun append(journal: String, owner: String = "owner-a", key: String = "same-key", raw: String = draft) =
        mvc.perform(
            trusted(post(path(journal)), owner).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(raw),
        ).andReturn().response

    private fun createJournal(): String {
        val response = mvc.perform(
            trusted(post("/finance/journals")).header("Idempotency-Key", "create-journal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"type":"study","title":"A note","keyContent":"Original","openQuestions":[],"occurredAt":"2026-08-12T14:30","timeZone":"Asia/Seoul"}"""),
        ).andReturn().response
        assertThat(response.status).isEqualTo(201)
        return mapper.readTree(response.contentAsString).get("journalId").asText()
    }

    private fun trusted(request: MockHttpServletRequestBuilder, owner: String = "owner-a") = request
        .header(FinanceTrustedContextFilter.HEADER_VERSION, FinanceTrustedContextFilter.CONTEXT_VERSION)
        .header(FinanceTrustedContextFilter.HEADER_SERVICE_ID, FinanceTrustedContextFilter.SERVICE_ID)
        .header(FinanceTrustedContextFilter.HEADER_SECRET, env.getRequiredProperty("finance.auth.trusted-context.service-secret"))
        .header(FinanceTrustedContextFilter.HEADER_AUDIENCE, FinanceTrustedContextFilter.AUDIENCE)
        .header(FinanceTrustedContextFilter.HEADER_SUBJECT,
            Base64.getUrlEncoder().withoutPadding().encodeToString(owner.toByteArray()),
        )
}
