package labs.ranikun.finance.generalanalysis.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

class LocalFakeGeneralAnalysisConditionTests {
    private val runner = ApplicationContextRunner().withUserConfiguration(Config::class.java)
        .withPropertyValues("spring.profiles.active=local")

    @Test
    fun `fake endpoint exists only when shared AI is disabled`() {
        runner.withPropertyValues("finance.shared-ai.enabled=false").run { assertThat(it).hasSingleBean(LocalFakeGeneralAnalysisController::class.java) }
        runner.withPropertyValues("finance.shared-ai.enabled=true").run { assertThat(it).doesNotHaveBean(LocalFakeGeneralAnalysisController::class.java) }
        runner.withPropertyValues("spring.profiles.active=production", "finance.shared-ai.enabled=false").run { assertThat(it).doesNotHaveBean(LocalFakeGeneralAnalysisController::class.java) }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(LocalFakeGeneralAnalysisController::class)
    class Config {
        @Bean fun syntheticLens(): labs.ranikun.finance.generalanalysis.application.SyntheticLensService = mock(labs.ranikun.finance.generalanalysis.application.SyntheticLensService::class.java)
        @Bean fun delegate(): GeneralAnalysisController = mock(GeneralAnalysisController::class.java)
    }
}
