package labs.ranikun.finance

import jakarta.persistence.EntityManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class MetricsTestEndpoints(
    private val entityManager: EntityManager,
) {
    @GetMapping("/__metrics-test/failure")
    fun failure(): Nothing = throw IllegalStateException("metrics integration test failure")

    @Transactional
    @GetMapping("/__metrics-test/jpa-timeout")
    fun jpaTimeout(): Any? {
        entityManager.createNativeQuery("SELECT set_config('statement_timeout', '100ms', true)").singleResult
        return entityManager.createNativeQuery("SELECT pg_sleep(1)").singleResult
    }
}
