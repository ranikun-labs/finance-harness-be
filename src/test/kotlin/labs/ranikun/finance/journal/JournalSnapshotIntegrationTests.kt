package labs.ranikun.finance.journal

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.application.CurrentUserPort
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter
import labs.ranikun.finance.journal.application.*
import labs.ranikun.finance.journal.persistence.JdbcJournalReadStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import tools.jackson.databind.json.JsonMapper
import java.sql.Connection
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

@AutoConfigureMockMvc
@SpringBootTest(properties = ["spring.datasource.hikari.maximum-pool-size=2"])
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test", "finance-trusted-context-reference")
class JournalSnapshotIntegrationTests {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var env: Environment
    private val mapper = JsonMapper.builder().build()
    private val owner = IdentityUserId("snapshot-owner")
    private fun trusted(r: MockHttpServletRequestBuilder) = r
        .header(FinanceTrustedContextFilter.HEADER_VERSION, "1")
        .header(FinanceTrustedContextFilter.HEADER_SERVICE_ID, "gateway-app")
        .header(FinanceTrustedContextFilter.HEADER_SECRET, env.getRequiredProperty("finance.auth.trusted-context.service-secret"))
        .header(FinanceTrustedContextFilter.HEADER_AUDIENCE, "finance-journal")
        .header(FinanceTrustedContextFilter.HEADER_SUBJECT, Base64.getUrlEncoder().withoutPadding().encodeToString(owner.value.toByteArray()))

    @Test fun detailMustNotCombineOldBodyRevisionWithCommittedNewQuestionsAcrossTwoConnections() {
        val raw = """{"type":"study","title":"A","keyContent":"body-A","openQuestions":["question-A"],"occurredAt":"2026-08-12T14:30","timeZone":"Asia/Seoul"}"""
        val created = mvc.perform(trusted(post("/finance/journals")).header("Idempotency-Key", "snapshot-race")
            .contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn().response
        assertThat(created.status).isEqualTo(201)
        val id = UUID.fromString(mapper.readTree(created.contentAsString).get("journalId").asText())
        jdbc.update("UPDATE journals SET revision=7 WHERE id=?", id)
        val firstRead = CountDownLatch(1)
        val committed = CountDownLatch(1)
        val questionsQueries = AtomicInteger()
        val pool = Executors.newSingleThreadExecutor()
        dataSource.connection.use { connectionA ->
            assertThat(connectionA.autoCommit).isTrue()
            assertThat(connectionA.transactionIsolation).isEqualTo(Connection.TRANSACTION_READ_COMMITTED)
            val pinned = JdbcTemplate(SingleConnectionDataSource(connectionA, true))
            val readerPid = pinned.queryForObject("SELECT pg_backend_pid()", Int::class.java)
            val delegate = JdbcJournalReadStore(pinned)
            val gate = object : JournalReadStore by delegate {
                override fun findDetail(owner: IdentityUserId, journalId: UUID): JournalDetailRecord? {
                    val record = delegate.findDetail(owner, journalId)
                    firstRead.countDown()
                    check(committed.await(20, TimeUnit.SECONDS))
                    return record
                }
                override fun findStudyOpenQuestions(owner: IdentityUserId, journalId: UUID): List<String> {
                    questionsQueries.incrementAndGet()
                    return delegate.findStudyOpenQuestions(owner, journalId)
                }
            }
            val service = JournalReadApplicationService(object : CurrentUserPort {
                override fun currentUserId() = owner
            }, gate)
            try {
                val reading = pool.submit<JournalDetail> { service.findDetail(id) }
                check(firstRead.await(20, TimeUnit.SECONDS))
                val patchBody = """{"expectedRevision":7,"fields":{"title":"B","keyContent":"body-B","openQuestions":["question-B"]}}"""
                val changed = mvc.perform(trusted(patch("/finance/journals/$id")).contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody)).andReturn().response
                assertThat(changed.status).isEqualTo(200)
                assertThat(mapper.readTree(changed.contentAsString).get("revision").asLong()).isEqualTo(8)
                val writerPoolPid = jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)
                assertThat(writerPoolPid).isNotEqualTo(readerPid)
                assertThat(jdbc.queryForObject("SELECT revision FROM journals WHERE id=?", Long::class.java, id)).isEqualTo(8)
                committed.countDown()
                val detail = reading.get(20, TimeUnit.SECONDS) as JournalDetail.Study
                println("SNAPSHOT_OBSERVATION isolation=READ_COMMITTED separateConnections=true title=${detail.title} revision=${detail.revision} questions=${detail.openQuestions}")
                assertThat(detail.title).isEqualTo("A")
                assertThat(detail.keyContent).isEqualTo("body-A")
                assertThat(detail.revision).isEqualTo(7)
                assertThat(detail.openQuestions).containsExactly("question-A")
                assertThat(questionsQueries.get()).isZero()
            } finally {
                committed.countDown()
                pool.shutdownNow()
            }
        }
    }
}
