package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@EnabledIfEnvironmentVariable(named = "FINANCE_BROWSER_READY_FILE", matches = ".+")
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["server.address=127.0.0.1", "management.server.address=127.0.0.1"])
@ActiveProfiles("test")
class FinanceBrowserSmokeServerTest {
    @LocalServerPort
    private var port: Int = 0

    @Test
    fun `serve browser smoke until completion marker`() {
        val ready = Path.of(requireNotNull(System.getenv("FINANCE_BROWSER_READY_FILE")))
        val done = Path.of(requireNotNull(System.getenv("FINANCE_BROWSER_DONE_FILE")))
        require(!Files.exists(ready) && !Files.exists(done)) { "Smoke marker paths must be fresh" }
        Files.writeString(ready, "http://127.0.0.1:$port")
        val deadline = System.nanoTime() + Duration.ofMinutes(8).toNanos()
        while (!Files.exists(done) && System.nanoTime() < deadline) Thread.sleep(100)
        check(Files.exists(done)) { "Browser smoke timed out" }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val base = URI.create(requireNotNull(System.getenv("FINANCE_SHARED_AI_TEST_URL")))
            require(base.scheme == "http" && base.host == "127.0.0.1" && base.port in 1..65535 &&
                base.userInfo == null && base.rawQuery == null && base.rawFragment == null &&
                (base.rawPath.isNullOrEmpty() || base.rawPath == "/"))
            registry.add("finance.shared-ai.enabled") { "true" }
            registry.add("finance.shared-ai.base-url") { base.toString() }
            registry.add("finance.shared-ai.tenant-reference") { "tenant:browser-test" }
        }
    }
}
