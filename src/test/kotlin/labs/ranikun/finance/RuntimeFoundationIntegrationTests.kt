package labs.ranikun.finance

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.postgresql.PostgreSQLContainer

@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@ActiveProfiles("test")
class RuntimeFoundationIntegrationTests {

    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var flyway: Flyway

    @Autowired
    lateinit var mockMvc: MockMvc

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
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    fun databaseFailureMakesReadinessUnavailableWithoutBreakingLiveness() {
        postgresContainer.stop()
        dataSource.close()

        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isServiceUnavailable)
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("UP"))
    }
}
