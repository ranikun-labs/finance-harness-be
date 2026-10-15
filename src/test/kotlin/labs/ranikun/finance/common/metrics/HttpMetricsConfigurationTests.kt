package labs.ranikun.finance.common.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HttpMetricsConfigurationTests {

    @Test
    fun `retains only bounded HTTP labels and removes request supplied values`() {
        val registry = SimpleMeterRegistry()
        registry.config().meterFilter(HttpMetricsConfiguration().boundedHttpServerMetricLabels())

        val counter = Counter.builder("http.server.requests")
            .tags(
                "method", "TRACE",
                "status", "418",
                "outcome", "UNMAPPED",
                "uri", "/finance/journals/account-54981023",
                "exception", "SqlFailureWithSensitiveMessage",
                "account", "account-54981023",
                "service", "tenant-specific-service",
                "environment", "production,tenant-54981023",
            )
            .register(registry)

        assertThat(counter.id.tags.associate { it.key to it.value })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(
                    "method" to "OTHER",
                    "status" to "OTHER",
                    "outcome" to "OTHER",
                    "service" to "finance-harness-be",
                    "environment" to "production",
                ),
            )
    }
}
