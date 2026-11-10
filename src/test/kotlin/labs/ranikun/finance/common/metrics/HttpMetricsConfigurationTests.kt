package labs.ranikun.finance.common.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Duration
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

    @Test
    fun `removes unbounded labels from active HTTP timers and caps only completed request timer`() {
        val filter = HttpMetricsConfiguration().boundedHttpServerMetricLabels()
        val active = filter.map(
            io.micrometer.core.instrument.Meter.Id(
                "http.server.requests.active",
                Tags.of("uri", "/account-54981023", "exception", "Secret"),
                null,
                null,
                Meter.Type.TIMER,
            ),
        )
        assertThat(active.tags).isEmpty()

        val completed = io.micrometer.core.instrument.Meter.Id(
            "http.server.requests",
            Tags.empty(),
            null,
            null,
            Meter.Type.TIMER,
        )
        val base = io.micrometer.core.instrument.distribution.DistributionStatisticConfig.builder().build()
        val bounded = filter.configure(completed, base)
        val activeConfig = filter.configure(completed.withName("http.server.requests.active"), base)

        assertThat(requireNotNull(bounded).maximumExpectedValue)
            .isEqualTo(Duration.ofSeconds(30).toNanos().toDouble())
        assertThat(requireNotNull(activeConfig).maximumExpectedValue).isNull()
    }
}
