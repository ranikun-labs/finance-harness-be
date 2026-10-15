package labs.ranikun.finance

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MetricsEndpointIntegrationTests {

    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var dataSource: HikariDataSource

    @Test
    fun `private scrape contains HTTP JVM Hikari and bounded JDBC samples`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute("SELECT 1") }
        }

        val applicationPort = environment.getRequiredProperty("local.server.port").toInt()
        val sensitivePath = "/__finance_metrics_private_path_7f31__/account-54981023"
        val applicationResponse = get("http://127.0.0.1:$applicationPort$sensitivePath")
        assertThat(applicationResponse.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value())

        val publicMetricsResponse = get("http://127.0.0.1:$applicationPort/actuator/prometheus")
        assertThat(publicMetricsResponse.statusCode()).isEqualTo(HttpStatus.NOT_FOUND.value())

        val managementPort = environment.getRequiredProperty("local.management.port").toInt()
        val metrics = get("http://127.0.0.1:$managementPort/actuator/prometheus").body()

        assertThat(metrics)
            .contains("http_server_requests_seconds_bucket")
            .contains("http_server_requests_seconds_count")
            .contains("status=\"404\"")
            .contains("jvm_memory_used_bytes")
            .contains("area=\"heap\"")
            .contains("area=\"nonheap\"")
            .contains("jvm_gc_pause_seconds")
            .contains("jvm_threads_live_threads")
            .contains("process_uptime_seconds")
            .contains("process_cpu_usage")
            .contains("hikaricp_connections_active")
            .contains("hikaricp_connections_max")
            .contains("hikaricp_connections_pending")
            .contains("ranikun_db_query_duration_seconds_bucket")
            .contains("operation=\"execute\"")
            .contains("le=\"0.005\"")
            .contains("le=\"0.001\"")
            .doesNotContain(sensitivePath)
            .doesNotContain("account-54981023")
            .doesNotContain("SELECT 1")
    }

    private fun get(url: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
