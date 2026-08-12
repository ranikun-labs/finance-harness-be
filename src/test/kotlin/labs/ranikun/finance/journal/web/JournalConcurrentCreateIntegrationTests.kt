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
            val futures = (0 until concurrency).map {
                executor.submit<String> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "workers did not start" }
                    val response = mockMvc.perform(
                        post("/finance/journals")
                            .contentType(MediaType.APPLICATION_JSON)
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

    private fun investmentJson(): String =
        """
        {
          "type": "investment",
          "assetName": "ETF",
          "occurredAt": "2026-08-12T14:30:15.123",
          "timeZone": "Asia/Seoul",
          "action": "buy",
          "reasoning": "concurrent create proof"
        }
        """.trimIndent()
}
