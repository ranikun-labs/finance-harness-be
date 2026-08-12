package labs.ranikun.finance.journal.application

import com.fasterxml.uuid.Generators
import org.springframework.stereotype.Component
import java.util.UUID

interface JournalIdGenerator {

    fun generate(): UUID
}

@Component
class JugJournalIdGenerator : JournalIdGenerator {

    override fun generate(): UUID = GENERATOR.generate()

    companion object {
        private val GENERATOR = Generators.timeBasedEpochGenerator()
    }
}
