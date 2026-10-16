package labs.ranikun.finance

import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.test.util.AopTestUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.test.context.ActiveProfiles
import java.sql.SQLException
import java.sql.DatabaseMetaData
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Import(TestcontainersConfiguration::class, MetricsTestEndpoints::class)
@AutoConfigureMetrics(export = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MetricsEndpointIntegrationTests {

    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var dataSource: HikariDataSource

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var meterRegistry: MeterRegistry

    @Test
    fun `private scrape contains HTTP JVM Hikari and bounded JDBC samples`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute("SELECT 1") }
        }
        assertThat(jdbcTemplate.queryForObject("SELECT 1", Int::class.javaObjectType)).isEqualTo(1)

        val applicationPort = environment.getRequiredProperty("local.server.port").toInt()
        val sensitivePath = "/__finance_metrics_private_path_7f31__/account-54981023"
        val applicationResponse = get("http://127.0.0.1:$applicationPort$sensitivePath")
        assertThat(applicationResponse.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())

        val publicMetricsResponse = get("http://127.0.0.1:$applicationPort/actuator/prometheus")
        assertThat(publicMetricsResponse.statusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value())
        assertThat(publicMetricsResponse.body()).doesNotContain("http_server_requests")
        assertThat(get("http://127.0.0.1:$applicationPort/finance/journals/00000000-0000-0000-0000-000000000000").statusCode())
            .isEqualTo(HttpStatus.NOT_FOUND.value())

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
        assertThat(metrics.lineSequence().count { it.startsWith("hikaricp_connections_active{") }).isEqualTo(1)
        assertPositiveSample(metrics, "ranikun_db_query_duration_seconds_count{", "operation=\"execute\"")
        assertPositiveSample(metrics, "ranikun_db_query_duration_seconds_count{", "operation=\"query\"")
        assertPositiveSample(metrics, "ranikun_db_query_duration_seconds_bucket{", "operation=\"query\"", "le=\"30.0\"")
    }

    @Test
    fun `real application and management requests record success 404 and genuine 500`() {
        val app = environment.getRequiredProperty("local.server.port").toInt()
        val management = environment.getRequiredProperty("local.management.port").toInt()

        assertThat(get("http://127.0.0.1:$app/finance/journals?limit=1").statusCode()).isEqualTo(200)
        assertThat(get("http://127.0.0.1:$management/actuator/health/readiness").statusCode()).isEqualTo(200)
        assertThat(get("http://127.0.0.1:$app/__metrics-test/failure").statusCode()).isEqualTo(500)
        assertThat(get("http://127.0.0.1:$app/__finance_metrics_private_path_7f31__/account-54981023").statusCode())
            .isEqualTo(500)
        assertThat(get("http://127.0.0.1:$app/finance/journals/00000000-0000-0000-0000-000000000000").statusCode())
            .isEqualTo(404)
        val publicMetricsResponse = get("http://127.0.0.1:$app/actuator/prometheus")
        assertThat(publicMetricsResponse.statusCode()).isEqualTo(500)
        assertThat(publicMetricsResponse.body()).doesNotContain("http_server_requests")

        val metrics = get("http://127.0.0.1:$management/actuator/prometheus").body()
        assertThat(metrics)
            .contains("http_server_requests_seconds_count{", "status=\"200\"", "status=\"404\"", "status=\"500\"")
            .contains("http_server_requests_seconds_bucket{", "le=\"0.005\"", "le=\"30.0\"")
            .doesNotContain("uri=", "exception=", "account-54981023")
        assertPositiveSample(metrics, "http_server_requests_seconds_count{", "status=\"200\"")
        assertPositiveSample(metrics, "http_server_requests_seconds_count{", "status=\"500\"")
        assertPositiveSample(metrics, "http_server_requests_seconds_bucket{", "status=\"500\"", "le=\"30.0\"")
    }

    @Test
    fun `PostgreSQL timeout is counted through JPA and raw JDBC while user cancel is excluded`() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("SET statement_timeout = '100ms'")
                val failure = assertThrows(SQLException::class.java) {
                    statement.executeQuery("SELECT pg_sleep(1)")
                }
                assertThat(failure.sqlState).isEqualTo("57014")
                statement.execute("RESET statement_timeout")
            }
        }
        assertThat(meterRegistry.find("ranikun.db.query.timeouts").tag("operation", "query").counter()?.count())
            .isEqualTo(1.0)

        // This endpoint executes pg_sleep through a transactional JPA query and lets the
        // normal MVC exception resolver classify the real PostgreSQL exception chain.
        assertThat(get("http://127.0.0.1:${environment.getRequiredProperty("local.server.port")}/__metrics-test/jpa-timeout").statusCode())
            .isEqualTo(500)
        assertThat(meterRegistry.find("ranikun.http.timeouts").tag("cause", "database").counter()?.count())
            .isEqualTo(1.0)
        assertThat(meterRegistry.find("ranikun.db.query.timeouts").tag("operation", "query").counter()?.count())
            .isEqualTo(2.0)
    }

    @Test
    fun `JDBC proxies preserve result set statement connection and Hikari eviction`() {
        val connection = dataSource.connection
        assertThat(connection.unwrap(java.sql.Connection::class.java)).isEqualTo(connection)
        assertThat(connection.isWrapperFor(java.sql.Connection::class.java)).isTrue()
        assertThat(connection.hashCode()).isEqualTo(connection.hashCode())
        assertThat(connection).isEqualTo(connection)

        val rawDataSource: HikariDataSource = AopTestUtils.getTargetObject(dataSource)
        rawDataSource.connection.use { rawConnection ->
            assertThat(connection == rawConnection).isFalse()
            assertThat(rawConnection == connection).isFalse()
            val rawStatement = rawConnection.createStatement()
            val wrappedStatement = connection.createStatement()
            try {
                assertThat(wrappedStatement == rawStatement).isFalse()
                assertThat(rawStatement == wrappedStatement).isFalse()
                assertThat(wrappedStatement.hashCode()).isEqualTo(System.identityHashCode(wrappedStatement))
                val rawResultSet = rawStatement.executeQuery("SELECT 1")
                val wrappedResultSet = wrappedStatement.executeQuery("SELECT 1")
                try {
                    assertThat(wrappedResultSet == rawResultSet).isFalse()
                    assertThat(rawResultSet == wrappedResultSet).isFalse()
                    assertThat(wrappedResultSet.hashCode()).isEqualTo(System.identityHashCode(wrappedResultSet))
                } finally {
                    rawResultSet.close()
                    wrappedResultSet.close()
                }
            } finally {
                rawStatement.close()
                wrappedStatement.close()
            }
        }

        val statement = connection.createStatement()
        assertThat(statement.hashCode()).isEqualTo(statement.hashCode())
        assertThat(statement).isEqualTo(statement)
        assertThat(statement.unwrap(java.sql.Statement::class.java)).isEqualTo(statement)
        assertThat(statement.isWrapperFor(java.sql.Statement::class.java)).isTrue()
        val resultSet = statement.executeQuery("SELECT 1")
        assertThat(resultSet.next()).isTrue()
        assertThat(resultSet.unwrap(java.sql.ResultSet::class.java)).isEqualTo(resultSet)
        assertThat(resultSet.isWrapperFor(java.sql.ResultSet::class.java)).isTrue()
        assertThat(resultSet.statement).isEqualTo(statement)
        assertThat(resultSet.statement.connection).isEqualTo(connection)

        dataSource.evictConnection(connection)
        resultSet.close()
        statement.close()
        connection.close()
        dataSource.connection.use { replacement ->
            assertThat(replacement.createStatement().use { it.executeQuery("SELECT 1").use { result -> result.next() } })
                .isTrue()
        }
    }

    @Test
    fun `metadata connection path preserves wrappers and records real PostgreSQL statement execution`() {
        val management = environment.getRequiredProperty("local.management.port").toInt()
        val beforeScrape = get("http://127.0.0.1:$management/actuator/prometheus").body()
        val beforeCount = scrapeSample(beforeScrape, "ranikun_db_query_duration_seconds_count{", "operation=\"execute\"") ?: 0.0
        val beforeSum = scrapeSample(beforeScrape, "ranikun_db_query_duration_seconds_sum{", "operation=\"execute\"") ?: 0.0
        val beforeBucket = scrapeSample(
            beforeScrape,
            "ranikun_db_query_duration_seconds_bucket{",
            "operation=\"execute\"",
            "le=\"30.0\"",
        ) ?: 0.0

        dataSource.connection.use { connection ->
            val metadata = connection.metaData
            assertThat(metadata.connection).isSameAs(connection)
            assertThat(metadata.isWrapperFor(DatabaseMetaData::class.java)).isTrue()
            assertThat(metadata.unwrap(DatabaseMetaData::class.java)).isSameAs(metadata)

            val postgresMetadataType = Class.forName("org.postgresql.jdbc.PgDatabaseMetaData")
            assertThat(metadata.isWrapperFor(postgresMetadataType)).isTrue()
            assertThat(metadata.unwrap(postgresMetadataType)).isInstanceOf(postgresMetadataType)

            metadata.getTables(null, "public", "journals", null).use { resultSet ->
                val metadataStatement = resultSet.statement
                if (metadataStatement != null) {
                    assertThat(metadataStatement.connection).isSameAs(connection)
                    assertThat(resultSet.statement).isSameAs(metadataStatement)
                }
            }

            metadata.connection.createStatement().use { statement ->
                assertThat(statement.connection).isSameAs(connection)
                assertThat(statement.execute("SELECT 1")).isTrue()
                statement.executeQuery("SELECT 1").use { resultSet ->
                    assertThat(resultSet.statement).isSameAs(statement)
                    assertThat(resultSet.statement.connection).isSameAs(connection)
                }
                val failure = assertThrows(SQLException::class.java) {
                    statement.execute("SELECT * FROM __finance_metrics_missing_relation_7f31__")
                }
                assertThat(failure.javaClass.name).isEqualTo("org.postgresql.util.PSQLException")
                assertThat(failure.sqlState).isEqualTo("42P01")
            }
        }

        val afterScrape = get("http://127.0.0.1:$management/actuator/prometheus").body()
        assertThat(scrapeSample(afterScrape, "ranikun_db_query_duration_seconds_count{", "operation=\"execute\"") ?: 0.0)
            .isEqualTo(beforeCount + 2.0)
        assertThat(scrapeSample(afterScrape, "ranikun_db_query_duration_seconds_sum{", "operation=\"execute\"") ?: 0.0)
            .isGreaterThan(beforeSum)
        assertThat(
            scrapeSample(
                afterScrape,
                "ranikun_db_query_duration_seconds_bucket{",
                "operation=\"execute\"",
                "le=\"30.0\"",
            ) ?: 0.0,
        ).isEqualTo(beforeBucket + 2.0)
        assertThat(afterScrape).doesNotContain("__finance_metrics_missing_relation_7f31__", "SELECT 1")
    }

    private fun get(url: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun assertPositiveSample(metrics: String, metric: String, vararg labels: String) {
        assertThat(
            metrics.lineSequence().any { line ->
                line.startsWith(metric) && labels.all(line::contains) &&
                    line.substringAfterLast(' ').toDoubleOrNull()?.let { it > 0.0 } == true
            },
        ).isTrue()
    }

    private fun scrapeSample(metrics: String, metric: String, vararg labels: String): Double? =
        metrics.lineSequence()
            .firstOrNull { line -> line.startsWith(metric) && labels.all(line::contains) }
            ?.substringAfterLast(' ')
            ?.toDoubleOrNull()
}
