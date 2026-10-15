package labs.ranikun.finance.common.metrics

import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.MeterBinder
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.aop.framework.ProxyFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class JdbcStatementDurationRecorder(
    private val meterRegistry: MeterRegistry,
) {
    private val timers = ConcurrentHashMap<String, Timer>()

    fun record(methodName: String, elapsedNanos: Long) {
        val operation = OPERATION_BY_METHOD[methodName] ?: return
        timers.computeIfAbsent(operation) { label ->
            Timer.builder(METRIC_NAME)
                .description("Time spent executing JDBC statements; SQL text is not retained")
                .tag("operation", label)
                .publishPercentileHistogram()
                .serviceLevelObjectives(*HISTOGRAM_BUCKETS)
                .register(meterRegistry)
        }.record(elapsedNanos, TimeUnit.NANOSECONDS)
    }

    companion object {
        private const val METRIC_NAME = "ranikun.db.query.duration"
        private val OPERATION_BY_METHOD = mapOf(
            "executeQuery" to "query",
            "executeUpdate" to "update",
            "executeLargeUpdate" to "update",
            "execute" to "execute",
            "executeBatch" to "batch",
            "executeLargeBatch" to "batch",
        )
        private val HISTOGRAM_BUCKETS = arrayOf(
            Duration.ofMillis(1),
            Duration.ofMillis(5),
            Duration.ofMillis(10),
            Duration.ofMillis(25),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofMillis(2500),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(15),
            Duration.ofSeconds(30),
        )
    }
}

@Configuration(proxyBeanMethods = false)
class JdbcStatementDurationConfiguration {

    @Bean
    fun jdbcStatementDurationRecorder(meterRegistry: MeterRegistry) =
        JdbcStatementDurationRecorder(meterRegistry)

    @Bean
    fun financeHikariPoolMetrics(dataSource: HikariDataSource): MeterBinder = MeterBinder { registry ->
        Gauge.builder("hikaricp.connections.active", dataSource) {
            it.hikariPoolMXBean?.activeConnections?.toDouble() ?: 0.0
        }.tag("pool", "finance-db").register(registry)
        Gauge.builder("hikaricp.connections.max", dataSource) {
            it.maximumPoolSize.toDouble()
        }.tag("pool", "finance-db").register(registry)
        Gauge.builder("hikaricp.connections.pending", dataSource) {
            it.hikariPoolMXBean?.threadsAwaitingConnection?.toDouble() ?: 0.0
        }.tag("pool", "finance-db").register(registry)
    }

    @Bean
    fun instrumentHikariJdbcStatements(
        recorderProvider: ObjectProvider<JdbcStatementDurationRecorder>,
    ): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (beanName != "dataSource" || bean !is HikariDataSource) return bean

            val proxyFactory = ProxyFactory(bean).apply {
                isProxyTargetClass = true
                addAdvice(MethodInterceptor { invocation ->
                    val result = invocation.proceed()
                    if (invocation.method.name in CONNECTION_ACQUISITION_METHODS && result is Connection) {
                        instrumentConnection(result, recorderProvider)
                    } else {
                        result
                    }
                })
            }
            return proxyFactory.proxy
        }
    }

    companion object {
        private val CONNECTION_ACQUISITION_METHODS = setOf("getConnection")
    }
}

private fun instrumentConnection(
    target: Connection,
    recorderProvider: ObjectProvider<JdbcStatementDurationRecorder>,
): Connection {
    lateinit var connectionProxy: Connection
    connectionProxy = Proxy.newProxyInstance(
        Connection::class.java.classLoader,
        arrayOf(Connection::class.java),
    ) { proxy, method, args ->
        if (method.name == "unwrap" && args?.firstOrNull() == Connection::class.java) return@newProxyInstance proxy
        if (method.name == "isWrapperFor" && args?.firstOrNull() == Connection::class.java) return@newProxyInstance true

        val result = invokeJdbc(target, method, args)
        if (result is Statement && method.name in STATEMENT_CREATION_METHODS) {
            instrumentStatement(result, connectionProxy, recorderProvider)
        } else {
            result
        }
    } as Connection
    return connectionProxy
}

private fun instrumentStatement(
    target: Statement,
    connectionProxy: Connection,
    recorderProvider: ObjectProvider<JdbcStatementDurationRecorder>,
): Statement {
    val statementType = when (target) {
        is CallableStatement -> CallableStatement::class.java
        is PreparedStatement -> PreparedStatement::class.java
        else -> Statement::class.java
    }
    return Proxy.newProxyInstance(
        statementType.classLoader,
        arrayOf(statementType),
    ) { proxy, method, args ->
        if (method.name == "getConnection") return@newProxyInstance connectionProxy
        if (method.name == "unwrap") {
            val requestedType = args?.firstOrNull() as? Class<*>
            if (requestedType != null && requestedType.isInstance(proxy)) return@newProxyInstance proxy
        }
        if (method.name == "isWrapperFor") {
            val requestedType = args?.firstOrNull() as? Class<*>
            if (requestedType != null && requestedType.isInstance(proxy)) return@newProxyInstance true
        }

        if (method.name !in STATEMENT_EXECUTION_METHODS) {
            return@newProxyInstance invokeJdbc(target, method, args)
        }

        val startedAt = System.nanoTime()
        try {
            invokeJdbc(target, method, args)
        } finally {
            recorderProvider.getObject().record(method.name, System.nanoTime() - startedAt)
        }
    } as Statement
}

private fun invokeJdbc(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
    method.invoke(target, *(args ?: emptyArray()))
} catch (exception: InvocationTargetException) {
    throw exception.targetException
}

private val STATEMENT_CREATION_METHODS = setOf("createStatement", "prepareStatement", "prepareCall")
private val STATEMENT_EXECUTION_METHODS = setOf(
    "executeQuery",
    "executeUpdate",
    "executeLargeUpdate",
    "execute",
    "executeBatch",
    "executeLargeBatch",
)
