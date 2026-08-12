package labs.ranikun.finance.common.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    fun financeClock(): Clock = Clock.systemUTC()
}
