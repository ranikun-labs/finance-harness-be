package labs.ranikun.finance.membership.persistence

import labs.ranikun.finance.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import javax.sql.DataSource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("test")
class FinanceMembershipPersistenceIntegrationTest {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var flyway: Flyway
    @Autowired lateinit var dataSource: DataSource
    private lateinit var store: PostgresFinanceMembershipStore

    @BeforeEach fun setup() {
        jdbc.update("DELETE FROM finance_membership_onboarding")
        store = PostgresFinanceMembershipStore(jdbc)
    }

    @Test fun `v6 and idempotent pending creation preserve separate owners`() {
        assertThat(flyway.info().applied().last().version.version).isEqualTo("6")
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        assertThat(store.ensurePending(a)).isTrue()
        val created = jdbc.queryForObject("SELECT created_at FROM finance_membership_onboarding WHERE account_id = ?", java.time.OffsetDateTime::class.java, a)
        assertThat(store.ensurePending(a)).isFalse()
        assertThat(store.ensurePending(b)).isTrue()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_membership_onboarding", Int::class.java)).isEqualTo(2)
        assertThat(jdbc.queryForObject("SELECT onboarding_status FROM finance_membership_onboarding WHERE account_id = ?", String::class.java, a)).isEqualTo("PENDING")
        assertThat(jdbc.queryForObject("SELECT created_at FROM finance_membership_onboarding WHERE account_id = ?", java.time.OffsetDateTime::class.java, a)).isEqualTo(created)
        assertThatThrownBy { jdbc.update("UPDATE finance_membership_onboarding SET onboarding_status = 'ACTIVE' WHERE account_id = ?", a) }
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test fun `concurrent requests create one pending row`() {
        val account = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = pool.invokeAll((1..12).map { Callable { store.ensurePending(account) } }).map { it.get() }
            assertThat(results.count { it }).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM finance_membership_onboarding WHERE account_id = ?", Int::class.java, account)).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `v6 upgrades a populated v5 without changing the existing journal`() {
        val schema = "membership_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        val journal = UUID.randomUUID()
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).target("5").load().migrate()
            jdbc.update(
                """
                INSERT INTO "$schema".journals
                  (id, identity_user_id, type, occurred_at, occurred_local_at, occurred_time_zone, created_at, updated_at)
                VALUES (?, 'existing-owner', 'study', now(), LOCALTIMESTAMP, 'UTC', now(), now())
                """.trimIndent(), journal,
            )
            jdbc.update(
                """INSERT INTO "$schema".study_journals (journal_id, journal_type, title, key_content)
                    VALUES (?, 'study', 'Original', 'Existing content')""", journal,
            )
            val before = jdbc.queryForMap("""SELECT * FROM "$schema".journals WHERE id = ?""", journal)
            Flyway.configure().dataSource(dataSource).schemas(schema).load().migrate()
            assertThat(jdbc.queryForMap("""SELECT * FROM "$schema".journals WHERE id = ?""", journal)).isEqualTo(before)
            assertThat(jdbc.queryForObject("""SELECT COUNT(*) FROM "$schema".finance_membership_onboarding""", Int::class.java)).isZero()
        } finally {
            jdbc.execute("""DROP SCHEMA IF EXISTS "$schema" CASCADE""")
        }
    }
}
