package labs.ranikun.finance.common.metrics

import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.aop.framework.ProxyFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class JdbcStatementDurationRecorder(
    private val meterRegistry: MeterRegistry,
) {
    private val timers = ConcurrentHashMap<String, Timer>()

    fun record(methodName: String, elapsedNanos: Long, failure: Throwable? = null) {
        val operation = OPERATION_BY_METHOD[methodName] ?: return
        timers.computeIfAbsent(operation) { label ->
            Timer.builder(METRIC_NAME)
                .description("Time spent executing JDBC statements; SQL text is not retained")
                .tag("operation", label)
                .publishPercentileHistogram()
                .serviceLevelObjectives(*HISTOGRAM_BUCKETS)
                .register(meterRegistry)
        }.record(elapsedNanos, TimeUnit.NANOSECONDS)

        if (failure != null && PostgresTimeoutClassifier.isTimeout(failure)) {
            meterRegistry.counter(TIMEOUT_METRIC, "operation", operation).increment()
        }
    }

    companion object {
        private const val METRIC_NAME = "ranikun.db.query.duration"
        private const val TIMEOUT_METRIC = "ranikun.db.query.timeouts"
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

object PostgresTimeoutClassifier {
    private const val QUERY_CANCELED_SQL_STATE = "57014"
    private const val MAX_CAUSE_DEPTH = 16

    fun isTimeout(error: Throwable): Boolean {
        val pending = ArrayDeque<Throwable>()
        val seen = HashSet<Throwable>()
        pending.add(error)
        var depth = 0
        while (pending.isNotEmpty() && depth++ < MAX_CAUSE_DEPTH) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is java.sql.SQLTimeoutException) return true
            if (current is SQLException && current.sqlState == QUERY_CANCELED_SQL_STATE) {
                val message = current.message.orEmpty().lowercase()
                if ("statement timeout" in message || "due to statement timeout" in message) return true
            }
            current.cause?.let(pending::addLast)
            if (current is SQLException) current.nextException?.let(pending::addLast)
        }
        return false
    }
}

@Configuration(proxyBeanMethods = false)
class JdbcStatementDurationConfiguration {

    @Bean
    fun jdbcStatementDurationRecorder(meterRegistry: MeterRegistry) =
        JdbcStatementDurationRecorder(meterRegistry)

    @Bean
    fun instrumentHikariJdbcStatements(
        recorderProvider: ObjectProvider<JdbcStatementDurationRecorder>,
    ): BeanPostProcessor = object : BeanPostProcessor {
        override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
            if (beanName != "dataSource" || bean !is HikariDataSource) return bean

            val proxyFactory = ProxyFactory(bean).apply {
                isProxyTargetClass = true
                addAdvice(MethodInterceptor { invocation ->
                    if (invocation.method.name == "evictConnection") {
                        invocation.arguments?.let { args ->
                            if (args.isNotEmpty()) args[0] = underlyingJdbcTarget(args[0])
                        }
                    }
                    val result = invocation.proceed()
                    if (invocation.method.name == "getConnection" && result is Connection) {
                        instrumentConnection(result, recorderProvider)
                    } else {
                        result
                    }
                })
            }
            return proxyFactory.proxy
        }
    }
}

private interface JdbcProxyMarker {
    val jdbcTarget: Any
}

private fun underlyingJdbcTarget(value: Any?): Any? =
    if (value is Proxy && Proxy.getInvocationHandler(value) is JdbcProxyMarker) {
        (Proxy.getInvocationHandler(value) as JdbcProxyMarker).jdbcTarget
    } else {
        value
    }

private fun instrumentConnection(
    target: Connection,
    recorderProvider: ObjectProvider<JdbcStatementDurationRecorder>,
): Connection = jdbcProxy(target, Connection::class.java, null, recorderProvider) as Connection

private fun instrumentMetadata(
    target: DatabaseMetaData,
    connectionProxy: Connection,
    recorderProvider: ObjectProvider<JdbcStatementDurationRecorder>,
): DatabaseMetaData =
    jdbcProxy(target, DatabaseMetaData::class.java, connectionProxy, recorderProvider) as DatabaseMetaData

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
    return jdbcProxy(target, statementType, connectionProxy, recorderProvider) as Statement
}

private fun instrumentResultSet(
    target: ResultSet,
    parentProxy: Any,
    recorderProvider: ObjectProvider<JdbcStatementDurationRecorder>,
): ResultSet = jdbcProxy(target, ResultSet::class.java, parentProxy, recorderProvider) as ResultSet

private fun jdbcProxy(
    target: Any,
    primaryInterface: Class<*>,
    parentProxy: Any?,
    recorderProvider: ObjectProvider<JdbcStatementDurationRecorder>,
): Any {
    val handler = JdbcInvocationHandler(target, primaryInterface, parentProxy, recorderProvider)
    return Proxy.newProxyInstance(primaryInterface.classLoader, arrayOf(primaryInterface), handler)
}

private class JdbcInvocationHandler(
    override val jdbcTarget: Any,
    private val primaryInterface: Class<*>,
    private val parentProxy: Any?,
    private val recorderProvider: ObjectProvider<JdbcStatementDurationRecorder>,
) : InvocationHandler, JdbcProxyMarker {
    private var metadataResultStatementResolved = false
    private var metadataResultStatementProxy: Statement? = null

    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        when (method.name) {
            "equals" -> return proxy === args?.firstOrNull()
            "hashCode" -> return System.identityHashCode(proxy)
            "toString" -> return "${primaryInterface.simpleName}Proxy@${System.identityHashCode(proxy).toString(16)}"
            "unwrap" -> {
                val requested = args?.firstOrNull() as? Class<*>
                if (requested != null && requested.isInstance(proxy)) return proxy
            }
            "isWrapperFor" -> {
                val requested = args?.firstOrNull() as? Class<*>
                if (requested != null && requested.isInstance(proxy)) return true
            }
        }

        if (jdbcTarget is Statement && method.name == "getConnection" && parentProxy is Connection) {
            return parentProxy
        }
        if (jdbcTarget is DatabaseMetaData && method.name == "getConnection" && parentProxy is Connection) {
            return parentProxy
        }
        if (jdbcTarget is ResultSet && method.name == "getStatement") {
            if (parentProxy is Statement) return parentProxy
            if (parentProxy is Connection) {
                if (!metadataResultStatementResolved) {
                    val statement = invokeJdbc(jdbcTarget, method, args) as Statement?
                    metadataResultStatementProxy = statement?.let {
                        instrumentStatement(it, parentProxy, recorderProvider)
                    }
                    metadataResultStatementResolved = true
                }
                return metadataResultStatementProxy
            }
        }

        val execution = method.name in STATEMENT_EXECUTION_METHODS && jdbcTarget is Statement
        val startedAt = if (execution) System.nanoTime() else 0L
        var failure: Throwable? = null
        try {
            val result = invokeJdbc(jdbcTarget, method, args)
            if (result is Statement && jdbcTarget is Connection && parentProxy == null) {
                return instrumentStatement(result, proxy as Connection, recorderProvider)
            }
            if (result is ResultSet && jdbcTarget is Statement && parentProxy is Connection) {
                return instrumentResultSet(result, proxy, recorderProvider)
            }
            if (result is DatabaseMetaData && jdbcTarget is Connection && parentProxy == null) {
                return instrumentMetadata(result, proxy as Connection, recorderProvider)
            }
            if (result is ResultSet && jdbcTarget is DatabaseMetaData && parentProxy is Connection) {
                return instrumentResultSet(result, parentProxy, recorderProvider)
            }
            return result
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (execution) {
                recorderProvider.getObject().record(method.name, System.nanoTime() - startedAt, failure)
            }
        }
    }

}

private fun invokeJdbc(target: Any, method: Method, args: Array<out Any?>?): Any? = try {
    method.invoke(target, *(args ?: emptyArray()))
} catch (exception: InvocationTargetException) {
    throw exception.targetException
}

private val STATEMENT_EXECUTION_METHODS = setOf(
    "executeQuery",
    "executeUpdate",
    "executeLargeUpdate",
    "execute",
    "executeBatch",
    "executeLargeBatch",
)
