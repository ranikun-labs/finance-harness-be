package labs.ranikun.finance.journal

import labs.ranikun.finance.TestcontainersConfiguration
import labs.ranikun.finance.identity.web.FinanceTrustedContextFilter
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import tools.jackson.databind.json.JsonMapper
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors

@AutoConfigureMockMvc
@SpringBootTest
@org.springframework.context.annotation.Import(TestcontainersConfiguration::class)
@ActiveProfiles("test", "finance-trusted-context-reference")
class JournalUpdateIntegrationTests {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var env: Environment
    private val mapper = JsonMapper.builder().build()
    private val body = """{"type":"study","title":"original","keyContent":"original content","openQuestions":["one"],"occurredAt":"2026-08-12T14:30","timeZone":"Asia/Seoul"}"""
    @BeforeEach fun clean() { listOf("journal_idempotency_records","study_open_questions","study_journals","investment_journals","journals").forEach { jdbc.update("DELETE FROM $it") } }
    private fun trusted(r: MockHttpServletRequestBuilder, who: String = "owner-a") = r
        .header(FinanceTrustedContextFilter.HEADER_VERSION,"1")
        .header(FinanceTrustedContextFilter.HEADER_SERVICE_ID,"gateway-app")
        .header(FinanceTrustedContextFilter.HEADER_SECRET,env.getRequiredProperty("finance.auth.trusted-context.service-secret"))
        .header(FinanceTrustedContextFilter.HEADER_AUDIENCE,"finance-journal")
        .header(FinanceTrustedContextFilter.HEADER_SUBJECT,Base64.getUrlEncoder().withoutPadding().encodeToString(who.toByteArray()))
    private fun create(raw: String = body): String {
        val response=mvc.perform(trusted(post("/finance/journals")).header("Idempotency-Key","edit-original").contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn().response
        assertThat(response.status).isEqualTo(201)
        return mapper.readTree(response.contentAsString).get("journalId").asText()
    }
    private fun edit(id: String, title: String, rev: Long = 0, who: String = "owner-a") = mvc.perform(trusted(patch("/finance/journals/$id"),who)
        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(mapOf("expectedRevision" to rev,"fields" to mapOf("title" to title,"keyContent" to "edited content","openQuestions" to listOf("new")))))).andReturn().response
    @Test fun ownStudyEditPreservesIdentityTimeAndCreationReplay() {
        val id=create();val before=jdbc.queryForMap("SELECT identity_user_id,type,occurred_at,occurred_local_at,occurred_time_zone,created_at FROM journals")
        val response=edit(id,"updated");assertThat(response.status).isEqualTo(200);assertThat(mapper.readTree(response.contentAsString).get("revision").asLong()).isEqualTo(1)
        assertThat(jdbc.queryForMap("SELECT identity_user_id,type,occurred_at,occurred_local_at,occurred_time_zone,created_at FROM journals")).isEqualTo(before)
        assertThat(jdbc.queryForObject("SELECT title FROM study_journals",String::class.java)).isEqualTo("updated")
        assertThat(jdbc.queryForObject("SELECT question FROM study_open_questions",String::class.java)).isEqualTo("new")
        val read=mvc.perform(trusted(get("/finance/journals/$id"))).andReturn().response
        assertThat(mapper.readTree(read.contentAsString).get("revision").asLong()).isEqualTo(1)
        val replay=create();assertThat(replay).isEqualTo(id)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_idempotency_records",Long::class.java)).isEqualTo(1)
    }
    @Test fun twoConcurrentDevicesCannotSilentlyOverwrite() {
        val id=create();val pool=Executors.newFixedThreadPool(2)
        try {
            val responses=pool.invokeAll(listOf(Callable { edit(id,"device-one").status },Callable { edit(id,"device-two").status })).map { it.get() }
            assertThat(responses).containsExactlyInAnyOrder(200,409)
            assertThat(jdbc.queryForObject("SELECT revision FROM journals",Long::class.java)).isEqualTo(1)
            assertThat(edit(id,"stale-device").status).isEqualTo(409)
            assertThat(jdbc.queryForObject("SELECT title FROM study_journals",String::class.java)).isIn("device-one","device-two")
        } finally { pool.shutdownNow() }
    }
    @Test fun foreignAndImmutableFieldSpoofCannotWrite() {
        val id=create();assertThat(edit(id,"foreign",who="owner-b").status).isEqualTo(404)
        val raw="""{"expectedRevision":0,"fields":{"title":"changed","keyContent":"x","openQuestions":[],"timeZone":"UTC"}}"""
        val result=mvc.perform(trusted(patch("/finance/journals/$id")).contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn().response
        assertThat(result.status).isEqualTo(400)
        assertThat(mvc.perform(patch("/finance/journals/$id").contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn().response.status).isEqualTo(403)
        assertThat(jdbc.queryForObject("SELECT revision FROM journals",Long::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT title FROM study_journals",String::class.java)).isEqualTo("original")
    }
    @Test fun investmentOwnFieldsAndNullEmotionRemainTyped() {
        val id=create("""{"type":"investment","assetName":"Asset","action":"watching","reasoning":"old","emotion":"관망","occurredAt":"2026-08-12T14:30","timeZone":"Asia/Seoul"}""")
        val raw="""{"expectedRevision":0,"fields":{"assetName":"Corrected","action":"interest","reasoning":"corrected reasoning","emotion":null}}"""
        val response=mvc.perform(trusted(patch("/finance/journals/$id")).contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn().response
        assertThat(response.status).isEqualTo(200)
        val row=jdbc.queryForMap("SELECT asset_name,action,reasoning,emotion FROM investment_journals")
        assertThat(row["asset_name"]).isEqualTo("Corrected");assertThat(row["action"]).isEqualTo("interest");assertThat(row["emotion"]).isNull()
        assertThat(jdbc.queryForObject("SELECT type FROM journals",String::class.java)).isEqualTo("investment")
    }
    @Test fun immutableContextAndUnsafeRevisionsFailClosedWithoutWrites() {
        val id=create()
        for (name in listOf("owner", "type", "createdAt", "occurredAt", "timeZone", "decisionContext", "retrospective")) {
            val fields=mapOf("title" to "bad", "keyContent" to "bad", "openQuestions" to emptyList<String>(), name to "spoof")
            val raw=mapper.writeValueAsString(mapOf("expectedRevision" to 0, "fields" to fields))
            assertThat(mvc.perform(trusted(patch("/finance/journals/$id")).contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn().response.status).isEqualTo(400)
        }
        for (revision in listOf("-1", "0.5", "9007199254740992")) {
            val raw="""{"expectedRevision":$revision,"fields":{"title":"bad","keyContent":"bad","openQuestions":[]}}"""
            assertThat(mvc.perform(trusted(patch("/finance/journals/$id")).contentType(MediaType.APPLICATION_JSON).content(raw)).andReturn().response.status).isEqualTo(400)
        }
        assertThat(jdbc.queryForObject("SELECT revision FROM journals",Long::class.java)).isZero()
        jdbc.update("UPDATE journals SET revision=9007199254740991")
        assertThat(edit(id,"overflow",9007199254740991).status).isEqualTo(409)
        assertThat(jdbc.queryForObject("SELECT title FROM study_journals",String::class.java)).isEqualTo("original")
    }
}
