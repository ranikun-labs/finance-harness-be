package labs.ranikun.finance

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FinanceHarnessBeApplication

fun main(args: Array<String>) {
	runApplication<FinanceHarnessBeApplication>(*args)
}
