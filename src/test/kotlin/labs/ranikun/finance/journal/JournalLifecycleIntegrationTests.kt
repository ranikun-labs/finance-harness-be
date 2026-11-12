package labs.ranikun.finance.journal

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.application.IdentityUserId
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter
import labs.ranikun.finance.journal.application.*
import labs.ranikun.finance.journal.persistence.JpaJournalIdempotencyStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class, JournalLifecycleIntegrationTests.GateConfiguration::class)
@ActiveProfiles("test", "finance-trusted-context-reference")
class JournalLifecycleIntegrationTests {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var env: Environment
    @Autowired lateinit var gate: GatedIdempotencyStore
    private val mapper = JsonMapper.builder().build()
    private val raw = """{"type":"study","title":"private-title","keyContent":"private-body","openQuestions":["second","first"],"occurredAt":"2026-08-12T14:30","timeZone":"Asia/Seoul"}"""
    @BeforeEach fun clean() {
        gate.enabled = false
        listOf("journal_idempotency_records", "study_open_questions", "study_journals", "investment_journals", "journals").forEach { jdbc.update("DELETE FROM $it") }
    }
    private fun trusted(r: MockHttpServletRequestBuilder, who: String = "owner-a") = r
        .header(FinanceTrustedContextFilter.HEADER_VERSION, "1")
        .header(FinanceTrustedContextFilter.HEADER_SERVICE_ID, "gateway-app")
        .header(FinanceTrustedContextFilter.HEADER_SECRET, env.getRequiredProperty("finance.auth.trusted-context.service-secret"))
        .header(FinanceTrustedContextFilter.HEADER_AUDIENCE, "finance-journal")
        .header(FinanceTrustedContextFilter.HEADER_SUBJECT, Base64.getUrlEncoder().withoutPadding().encodeToString(who.toByteArray()))
    private fun postCreate(key: String = "life-key", body: String = raw) = mvc.perform(trusted(post("/finance/journals"))
        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().response
    private fun create(key: String = "life-key", body: String = raw): String {
        val r=postCreate(key,body);assertThat(r.status).isEqualTo(201)
        return mapper.readTree(r.contentAsString).get("journalId").asText()
    }
    private fun transition(id: String, action: String, rev: Long, who: String = "owner-a") = mvc.perform(trusted(post("/finance/journals/$id/$action"),who)
        .contentType(MediaType.APPLICATION_JSON).content("""{"expectedRevision":$rev}""")).andReturn().response
    private fun detail(id: String, who: String = "owner-a") = mvc.perform(trusted(get("/finance/journals/$id"),who)).andReturn().response

    @Test fun softDeleteHidesRawRetainsOriginalReplayAndDirectRestoreAdvancesRevision() {
        val id=create()
        val before=jdbc.queryForMap("SELECT identity_user_id,type,occurred_at,occurred_local_at,occurred_time_zone,created_at FROM journals")
        val fingerprint=jdbc.queryForObject("SELECT request_fingerprint FROM journal_idempotency_records",String::class.java)
        assertThat(mapper.readTree(detail(id).contentAsString).get("openQuestions").toList().map { it.asText() }).containsExactly("second","first")
        assertThat(transition(id,"delete",0).status).isEqualTo(200)
        assertThat(transition(id,"delete",0).status).isEqualTo(200)
        assertThat(detail(id).status).isEqualTo(404)
        val list=mvc.perform(trusted(get("/finance/journals"))).andReturn().response
        assertThat(mapper.readTree(list.contentAsString).get("items").size()).isZero()
        val edit=mvc.perform(trusted(patch("/finance/journals/$id")).contentType(MediaType.APPLICATION_JSON)
            .content("""{"expectedRevision":1,"fields":{"title":"bad","keyContent":"bad","openQuestions":[]}}""")).andReturn().response
        assertThat(edit.status).isEqualTo(404)
        val tombstone=mvc.perform(trusted(get("/finance/journals/deleted"))).andReturn().response
        assertThat(tombstone.status).isEqualTo(200)
        assertThat(tombstone.contentAsString).doesNotContain("private-title","private-body","second","first")
        val item=mapper.readTree(tombstone.contentAsString).get("items").first()
        assertThat(item.get("journalId").asText()).isEqualTo(id)
        assertThat(item.get("revision").asLong()).isEqualTo(1)
        assertThat(postCreate().status).isEqualTo(410)
        assertThat(postCreate(body=raw.replace("private-title","different")).status).isEqualTo(409)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journals",Long::class.java)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM study_open_questions",Long::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT request_fingerprint FROM journal_idempotency_records",String::class.java)).isEqualTo(fingerprint)
        assertThat(transition(id,"restore",1).status).isEqualTo(200)
        assertThat(transition(id,"restore",1).status).isEqualTo(200)
        assertThat(transition(id,"delete",0).status).isEqualTo(409)
        val restored=detail(id);assertThat(restored.status).isEqualTo(200)
        assertThat(mapper.readTree(restored.contentAsString).get("revision").asLong()).isEqualTo(2)
        assertThat(create()).isEqualTo(id)
        assertThat(jdbc.queryForMap("SELECT identity_user_id,type,occurred_at,occurred_local_at,occurred_time_zone,created_at FROM journals")).isEqualTo(before)
    }

    @Test fun concurrentDeleteAndEditHaveOneWinnerAndNeverAdoptLatestRevisionAutomatically() {
        val id=create();val pool=Executors.newFixedThreadPool(2)
        try {
            val statuses=pool.invokeAll(listOf(Callable{transition(id,"delete",0).status},Callable{
                mvc.perform(trusted(patch("/finance/journals/$id")).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"expectedRevision":0,"fields":{"title":"edited","keyContent":"edited","openQuestions":[]}}""")).andReturn().response.status
            })).map { it.get() }
            assertThat(statuses.count { it==200 }).isEqualTo(1)
            assertThat(statuses.count { it==404||it==409 }).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT revision FROM journals",Long::class.java)).isEqualTo(1)
        } finally {pool.shutdownNow()}
    }

    @Test fun actualUniqueCollisionRecoveryDoesNotResurrectADeletedWinner() {
        gate.enabled=true;gate.firstRead=CountDownLatch(1);gate.release=CountDownLatch(1)
        val pool=Executors.newSingleThreadExecutor { r->Thread(r,"create-loser") }
        try {
            val loser=pool.submit<Int>{postCreate("recovery-race").status}
            check(gate.firstRead.await(20,TimeUnit.SECONDS))
            val id=create("recovery-race")
            assertThat(transition(id,"delete",0).status).isEqualTo(200)
            gate.release.countDown()
            assertThat(loser.get(20,TimeUnit.SECONDS)).isEqualTo(410)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journals",Long::class.java)).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_idempotency_records",Long::class.java)).isEqualTo(1)
            assertThat(detail(id).status).isEqualTo(404)
        } finally {gate.release.countDown();gate.enabled=false;pool.shutdownNow()}
    }

    @Test fun emptyQuestionsForeignMalformedAndUnsafeLifecycleInputsStayClosed() {
        val id=create(body=raw.replace("[\"second\",\"first\"]","[]"))
        assertThat(mapper.readTree(detail(id).contentAsString).get("openQuestions").size()).isZero()
        assertThat(detail(id,"owner-b").status).isEqualTo(404)
        assertThat(detail(UUID.randomUUID().toString()).status).isEqualTo(404)
        assertThat(transition(id,"delete",0,"owner-b").status).isEqualTo(404)
        assertThat(mvc.perform(post("/finance/journals/$id/delete").contentType(MediaType.APPLICATION_JSON).content("""{"expectedRevision":0}""")).andReturn().response.status).isEqualTo(403)
        for (rawBody in listOf("""{"expectedRevision":-1}""","""{"expectedRevision":0.5}""","""{"expectedRevision":9007199254740992}""","""{"expectedRevision":0,"owner":"other"}""")) {
            assertThat(mvc.perform(trusted(post("/finance/journals/$id/delete")).contentType(MediaType.APPLICATION_JSON).content(rawBody)).andReturn().response.status).isEqualTo(400)
        }
        assertThat(mvc.perform(trusted(delete("/finance/journals/$id"))).andReturn().response.status).isEqualTo(403)
        jdbc.update("UPDATE journals SET revision=9007199254740991 WHERE id=?",UUID.fromString(id))
        assertThat(transition(id,"delete",9007199254740991).status).isEqualTo(409)
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM journals",Boolean::class.java)).isTrue()
    }

    @Test fun investmentChildrenRemainUnchangedAcrossDeleteAndRestore() {
        val body="""{"type":"investment","assetName":"Synthetic Asset","action":"watching","reasoning":"raw reasoning","occurredAt":"2026-08-12T14:30","timeZone":"Asia/Seoul"}"""
        val id=create(body=body)
        val child=jdbc.queryForMap("SELECT * FROM investment_journals")
        assertThat(transition(id,"delete",0).status).isEqualTo(200)
        val foreign=mvc.perform(trusted(get("/finance/journals/deleted"),"owner-b")).andReturn().response
        assertThat(mapper.readTree(foreign.contentAsString).get("items").size()).isZero()
        assertThat(transition(id,"restore",1,"owner-b").status).isEqualTo(404)
        assertThat(jdbc.queryForMap("SELECT * FROM investment_journals")).isEqualTo(child)
        assertThat(transition(id,"restore",1).status).isEqualTo(200)
        assertThat(jdbc.queryForMap("SELECT * FROM investment_journals")).isEqualTo(child)
        val restored=detail(id)
        assertThat(mapper.readTree(restored.contentAsString).get("assetName").asText()).isEqualTo("Synthetic Asset")
        assertThat(mapper.readTree(restored.contentAsString).get("revision").asLong()).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_idempotency_records",Long::class.java)).isEqualTo(1)
    }

    @TestConfiguration class GateConfiguration {
        @Bean @Primary fun gatedStore(delegate: JpaJournalIdempotencyStore)=GatedIdempotencyStore(delegate)
    }
    class GatedIdempotencyStore(private val delegate: JpaJournalIdempotencyStore): JournalIdempotencyStore by delegate {
        @Volatile var enabled=false
        var firstRead=CountDownLatch(1)
        var release=CountDownLatch(1)
        override fun find(owner: IdentityUserId, operation: String, idempotencyKey: String): JournalIdempotencyRecord? {
            val row=delegate.find(owner,operation,idempotencyKey)
            if(enabled&&Thread.currentThread().name=="create-loser"&&idempotencyKey=="recovery-race"&&row==null){
                firstRead.countDown();check(release.await(20,TimeUnit.SECONDS))
            }
            return row
        }
    }
}
