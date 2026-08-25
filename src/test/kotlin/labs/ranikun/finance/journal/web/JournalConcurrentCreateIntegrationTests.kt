package labs.ranikun.finance.journal.web

import labs.ranikun.finance.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@AutoConfigureMockMvc
@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class JournalConcurrentCreateIntegrationTests {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun clearJournalRows() {
        jdbcTemplate.update("DELETE FROM journal_idempotency_records")
        jdbcTemplate.update("DELETE FROM study_open_questions")
        jdbcTemplate.update("DELETE FROM study_journals")
        jdbcTemplate.update("DELETE FROM investment_journals")
        jdbcTemplate.update("DELETE FROM journals")
    }

    @Test
    fun concurrentCreatesProduceDistinctUuidV7IdsAndRows() {
        val concurrency = 8
        val ready = CountDownLatch(concurrency)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(concurrency)

        try {
            val futures = (0 until concurrency).map { worker ->
                executor.submit<String> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "workers did not start" }
                    val response = mockMvc.perform(
                        post("/finance/journals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "rpl50-distinct-$worker")
                            .content(investmentJson()),
                    ).andReturn().response
                    check(response.status == 201) { "unexpected status ${response.status}" }
                    response.getHeader(HttpHeaders.LOCATION)
                        ?.substringAfterLast('/')
                        ?: error("missing Location")
                }
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val ids = futures.map { UUID.fromString(it.get(30, TimeUnit.SECONDS)) }

            assertThat(ids).hasSize(concurrency).doesNotHaveDuplicates()
            assertThat(ids).allMatch { it.version() == 7 }
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM journals", Int::class.java))
                .isEqualTo(concurrency)
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM investment_journals", Int::class.java))
                .isEqualTo(concurrency)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentSameKeyAndSamePayloadConvergesToOneJournalAndRecord() {
        val concurrency = 8
        val ready = CountDownLatch(concurrency)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(concurrency)

        try {
            val futures = (0 until concurrency).map {
                executor.submit<HttpResult> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "workers did not start" }
                    mockMvc.perform(
                        post("/finance/journals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "same-concurrent-key")
                            .content(investmentJson()),
                    ).andReturn().response.let { response ->
                        HttpResult(
                            status = response.status,
                            journalId = response.getHeader(HttpHeaders.LOCATION)?.substringAfterLast('/'),
                            body = response.contentAsString,
                        )
                    }
                }
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }
            val journalIds = results.mapNotNull { it.journalId }

            assertThat(results).allMatch { it.status == 201 }
            assertThat(journalIds).hasSize(concurrency)
            assertThat(journalIds.toSet()).hasSize(1)
            assertThat(journalIds.toSet().single().let(UUID::fromString).version()).isEqualTo(7)
            assertThat(count("journals")).isEqualTo(1)
            assertThat(count("investment_journals")).isEqualTo(1)
            assertThat(count("journal_idempotency_records")).isEqualTo(1)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentSameKeyAndDifferentPayloadProducesOneWinnerAndOneConflict() {
        val concurrency = 2
        val ready = CountDownLatch(concurrency)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(concurrency)

        try {
            val futures = listOf("ETF", "BOND").map { assetName ->
                executor.submit<HttpResult> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "workers did not start" }
                    mockMvc.perform(
                        post("/finance/journals")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "different-concurrent-key")
                            .content(investmentJson(assetName)),
                    ).andReturn().response.let { response ->
                        HttpResult(
                            status = response.status,
                            journalId = response.getHeader(HttpHeaders.LOCATION)?.substringAfterLast('/'),
                            body = response.contentAsString,
                        )
                    }
                }
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val results = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertThat(results.map { it.status }).containsExactlyInAnyOrder(201, 409)
            assertThat(results.single { it.status == 409 }.body)
                .contains("idempotency_key_reused")
            assertThat(results.single { it.status == 201 }.journalId).isNotBlank()
            assertThat(count("journals")).isEqualTo(1)
            assertThat(count("journal_idempotency_records")).isEqualTo(1)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun count(table: String): Int = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM $table",
        Int::class.java,
    ) ?: error("Missing count for $table")

    private fun investmentJson(assetName: String = "ETF"): String =
        """
        {
          "type": "investment",
          "assetName": "$assetName",
          "occurredAt": "2026-08-12T14:30:15.123",
          "timeZone": "Asia/Seoul",
          "action": "buy",
          "reasoning": "concurrent create proof"
        }
        """.trimIndent()

    private data class HttpResult(
        val status: Int,
        val journalId: String?,
        val body: String,
    )
}
