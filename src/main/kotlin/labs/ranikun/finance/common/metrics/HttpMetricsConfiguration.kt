package labs.ranikun.finance.common.metrics

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order

@Configuration(proxyBeanMethods = false)
class HttpMetricsConfiguration {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    fun boundedHttpServerMetricLabels(): MeterFilter = object : MeterFilter {
        override fun map(id: Meter.Id): Meter.Id {
            if (id.name != HTTP_SERVER_REQUESTS) return id

            val safeTags = id.tags.mapNotNull { tag ->
                when (tag.key) {
                    "method" -> Tag.of(tag.key, tag.value.takeIf(ALLOWED_METHODS::contains) ?: "OTHER")
                    "status" -> Tag.of(tag.key, tag.value.takeIf(ALLOWED_STATUSES::contains) ?: "OTHER")
                    "outcome" -> Tag.of(tag.key, tag.value.takeIf(ALLOWED_OUTCOMES::contains) ?: "OTHER")
                    "service" -> Tag.of(tag.key, SERVICE_NAME)
                    "environment" -> Tag.of(tag.key, boundedEnvironment(tag.value))
                    else -> null
                }
            }
            return id.replaceTags(safeTags)
        }
    }

    private fun boundedEnvironment(value: String): String = when {
        "production" in value.split(',') -> "production"
        "test" in value.split(',') -> "test"
        "local" in value.split(',') -> "local"
        value == "default" -> "default"
        else -> "other"
    }

    companion object {
        private const val HTTP_SERVER_REQUESTS = "http.server.requests"
        private const val SERVICE_NAME = "finance-harness-be"
        private val ALLOWED_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        private val ALLOWED_STATUSES = setOf(
            "200", "201", "202", "204", "301", "302", "304", "400", "401", "403", "404", "405",
            "409", "422", "429", "500", "501", "502", "503", "504",
        )
        private val ALLOWED_OUTCOMES = setOf("INFORMATIONAL", "SUCCESS", "REDIRECTION", "CLIENT_ERROR", "SERVER_ERROR")
    }
}
