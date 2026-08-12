package labs.ranikun.finance.common.config

import org.springframework.context.annotation.Configuration
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.annotation.RollbackOn

@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(rollbackOn = RollbackOn.ALL_EXCEPTIONS)
class TransactionConfiguration
