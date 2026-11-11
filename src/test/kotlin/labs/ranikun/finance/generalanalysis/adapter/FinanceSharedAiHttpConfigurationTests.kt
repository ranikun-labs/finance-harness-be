package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.FinanceExecutionContextFactory
import labs.ranikun.finance.generalanalysis.application.FinanceSharedAiPort
import labs.ranikun.finance.generalanalysis.application.GeneralAnalysisExecutionPort
import labs.ranikun.finance.identity.application.IdentityUserId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class FinanceSharedAiHttpConfigurationTests {
    @Test
    fun `local test defaults keep deterministic executor and shared HTTP off`() {
        runner("test").run { context ->
            assertThat(context.startupFailure).isNull()
            assertThat(context.getBeansOfType(GeneralAnalysisExecutionPort::class.java)).hasSize(1)
            assertThat(context.getBean(GeneralAnalysisExecutionPort::class.java))
                .isInstanceOf(DeterministicGeneralAnalysisExecutionAdapter::class.java)
            assertThat(context.getBeansOfType(FinanceSharedAiPort::class.java)).isEmpty()
        }
    }

    @Test
    fun `local opt in selects HTTP executor and uses explicit tenant property`() {
        runner(
            "local",
            "finance.shared-ai.enabled=true",
            "finance.shared-ai.base-url=http://127.0.0.1:8080",
            "finance.shared-ai.tenant-reference=tenant:explicit-local-test",
        ).run { context ->
            assertThat(context.startupFailure).isNull()
            assertThat(context.getBeansOfType(GeneralAnalysisExecutionPort::class.java)).hasSize(1)
            assertThat(context.getBean(GeneralAnalysisExecutionPort::class.java))
                .isInstanceOf(FinanceGeneralAnalysisExecutionAdapter::class.java)
            assertThat(context.getBeansOfType(DeterministicGeneralAnalysisExecutionAdapter::class.java)).isEmpty()
            assertThat(context.getBeansOfType(FinanceSharedAiPort::class.java)).hasSize(1)
            val requestContext = context.getBean(FinanceExecutionContextFactory::class.java).create(
                IdentityUserId("test-principal"),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
                java.util.UUID.fromString("00000000-0000-0000-0000-000000000003"),
            )
            assertThat(requestContext.tenantReference).isEqualTo("tenant:explicit-local-test")
            assertThat(requestContext.principalReference).isEqualTo("test-principal")
        }
    }

    @Test
    fun `production profile never creates the outbound HTTP bean`() {
        runner(
            "production,local",
            "finance.shared-ai.enabled=true",
            "finance.shared-ai.base-url=http://127.0.0.1:8080",
            "finance.shared-ai.tenant-reference=tenant:must-not-activate",
        ).run { context ->
            assertThat(context.startupFailure).isNull()
            assertThat(context.getBeansOfType(FinanceSharedAiPort::class.java)).isEmpty()
            assertThat(context.getBeansOfType(GeneralAnalysisExecutionPort::class.java)).isEmpty()
        }
    }

    @Test
    fun `prod test profiles never create the outbound HTTP bean`() {
        runner(
            "prod,test",
            "finance.shared-ai.enabled=true",
            "finance.shared-ai.base-url=http://127.0.0.1:8080",
            "finance.shared-ai.tenant-reference=tenant:must-not-activate",
        ).run { context ->
            assertThat(context.startupFailure).isNull()
            assertThat(context.getBeansOfType(FinanceSharedAiPort::class.java)).isEmpty()
            assertThat(context.getBeansOfType(GeneralAnalysisExecutionPort::class.java)).isEmpty()
        }
    }

    @Test
    fun `enabled local client fails startup without an explicit tenant`() {
        runner(
            "local",
            "finance.shared-ai.enabled=true",
            "finance.shared-ai.base-url=http://127.0.0.1:8080",
        ).run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure).hasMessageContaining("tenant reference must be configured")
        }
    }

    private fun runner(profile: String, vararg propertyValues: String) = ApplicationContextRunner()
        .withUserConfiguration(
            FinanceSharedAiHttpConfiguration::class.java,
            DeterministicGeneralAnalysisExecutionAdapter::class.java,
        )
        .withInitializer { context -> context.environment.setActiveProfiles(*profile.split(',').toTypedArray()) }
        .withPropertyValues(*propertyValues)
}
