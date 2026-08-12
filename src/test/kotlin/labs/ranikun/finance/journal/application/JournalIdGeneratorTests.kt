package labs.ranikun.finance.journal.application

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class JournalIdGeneratorTests {

    private val generator = JugJournalIdGenerator()

    @Test
    fun generatedIdsAreUuidV7WithRfcVariantAndAreUnique() {
        val ids = (1..100).map { generator.generate() }

        assertThat(ids).hasSize(100).doesNotHaveDuplicates()
        assertThat(ids).allSatisfy { id ->
            assertThat(id.version()).isEqualTo(7)
            assertThat(id.variant()).isEqualTo(2)
        }
    }

    @Test
    fun generatorSeamCanBeMadeDeterministicForApplicationTests() {
        val fixedId = UUID.fromString("0190e8f0-8a00-7000-8000-000000000001")
        val fixedGenerator = FixedJournalIdGenerator(fixedId)

        assertThat(fixedGenerator.generate()).isEqualTo(fixedId)
        assertThat(fixedGenerator.generate()).isEqualTo(fixedId)
    }

    private class FixedJournalIdGenerator(private val id: UUID) : JournalIdGenerator {
        override fun generate(): UUID = id
    }
}
