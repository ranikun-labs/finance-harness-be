package labs.ranikun.finance

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RuntimeFoundationIntegrationTests {

    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var flyway: Flyway

    @Autowired
    lateinit var postgresContainer: PostgreSQLContainer

    @Autowired
    lateinit var dataSource: HikariDataSource

    @Test
    fun applicationContextStartsAgainstPostgreSQLAndAppliesJournalMigration() {
        assertThat(flyway.info().applied().mapNotNull { it.version?.version })
            .containsExactly("1", "2")
        assertThat(dataSource.connection.use { connection ->
            connection.metaData.getTables(null, null, "journals", null).use { tables -> tables.next() }
        }).isTrue()
        assertThat(dataSource.connection.use { connection ->
            connection.metaData.getTables(null, null, "journal_idempotency_records", null).use { tables ->
                tables.next()
            }
        }).isTrue()
    }

    @Test
    fun runtimeConfigurationKeepsFlywayAndHibernateAsSchemaOwners() {
        assertThat(environment.getProperty("spring.flyway.enabled")).isEqualTo("true")
        assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration")
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate")).isEqualTo("false")
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate")
        assertThat(environment.getProperty("spring.jpa.open-in-view")).isEqualTo("false")
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful")
    }

    @Test
    fun actuatorExposesHealthyLivenessAndDatabaseBackedReadiness() {
        val management = managementBaseUrl()
        val health = get("$management/actuator/health")
        val liveness = get("$management/actuator/health/liveness")
        val readiness = get("$management/actuator/health/readiness")
        assertThat(health.statusCode()).isEqualTo(200)
        assertThat(health.body()).contains("\"status\":\"UP\"")
        assertThat(liveness.statusCode()).isEqualTo(200)
        assertThat(liveness.body()).contains("\"status\":\"UP\"")
        assertThat(readiness.statusCode()).isEqualTo(200)
        assertThat(readiness.body()).contains("\"status\":\"UP\"")
        assertThat(environment.getRequiredProperty("local.server.port").toInt())
            .isNotEqualTo(environment.getRequiredProperty("local.management.port").toInt())
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    fun databaseFailureMakesReadinessUnavailableWithoutBreakingLiveness() {
        postgresContainer.stop()
        dataSource.close()

        assertThat(get("${managementBaseUrl()}/actuator/health/readiness").statusCode()).isEqualTo(503)
        val liveness = get("${managementBaseUrl()}/actuator/health/liveness")
        assertThat(liveness.statusCode()).isEqualTo(200)
        assertThat(liveness.body()).contains("\"status\":\"UP\"")
    }

    private fun managementBaseUrl(): String =
        "http://127.0.0.1:${environment.getRequiredProperty("local.management.port")}"

    private fun get(url: String): HttpResponse<String> =
        HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(url)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
}
