package labs.ranikun.finance.generalanalysis.adapter

import labs.ranikun.finance.generalanalysis.application.*
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** Fixed invented fixtures, never network or live market data. */
@Component
@Profile("!prod & !production & (local | test)")
@ConditionalOnProperty(prefix = "finance.shared-ai", name = ["enabled"], havingValue = "false", matchIfMissing = true)
class InMemorySyntheticLookup : SyntheticLookup {
    override fun basic(scenario: SyntheticScenario): SyntheticBasic {
        val (sector, macro) = when (scenario) { SyntheticScenario.SECTOR -> 0.8 to 0.4; SyntheticScenario.MACRO -> 0.3 to 0.9; SyntheticScenario.WEAK -> 0.2 to 0.3 }
        val source = "synthetic-basic:${scenario.name}"
        val now = Instant.now()
        return SyntheticBasic(sector, macro, listOf(SyntheticFact("sector", source, sector.toString()), SyntheticFact("macro", source, macro.toString())), UUID.randomUUID().toString(), now, now.plusSeconds(60))
    }
    override fun supplemental(scenario: SyntheticScenario, lens: SyntheticNextLens, snapshotId: String): SyntheticSupplement = SyntheticSupplement(snapshotId, listOf(SyntheticFact("supplement:${lens.name}", "synthetic-supplement:${scenario.name}:${lens.name}", when (lens) { SyntheticNextLens.SECTOR -> "Invented sector concentration detail"; SyntheticNextLens.MACRO -> "Invented macro context detail" })))
}
