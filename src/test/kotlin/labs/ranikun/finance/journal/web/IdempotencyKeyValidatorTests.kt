package labs.ranikun.finance.journal.web

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IdempotencyKeyValidatorTests {

    private val validator = IdempotencyKeyValidator()

    @Test
    fun acceptsAValidKeyWithoutTrimmingOrCaseFolding() {
        assertThat(validator.validate(listOf("A.Key_~-09"))).isEqualTo("A.Key_~-09")
    }

    @Test
    fun rejectsMissingAndBlankKeysAsRequired() {
        assertThatThrownBy { validator.validate(emptyList()) }
            .isInstanceOfSatisfying(InvalidJournalRequestException::class.java) {
                assertThat(it.fieldErrors).containsExactly(JournalFieldError("Idempotency-Key", "required"))
            }
        assertThatThrownBy { validator.validate(listOf("  ")) }
            .isInstanceOfSatisfying(InvalidJournalRequestException::class.java) {
                assertThat(it.fieldErrors).containsExactly(JournalFieldError("Idempotency-Key", "required"))
            }
    }

    @Test
    fun rejectsTooLongKeysWithTheDedicatedCode() {
        assertThatThrownBy { validator.validate(listOf("a".repeat(129))) }
            .isInstanceOfSatisfying(InvalidJournalRequestException::class.java) {
                assertThat(it.fieldErrors).containsExactly(JournalFieldError("Idempotency-Key", "too_long"))
            }
    }

    @Test
    fun rejectsInvalidDuplicateAndCommaJoinedValues() {
        listOf("-leading", "has space", "a,b").forEach { value ->
            assertThatThrownBy { validator.validate(listOf(value)) }
                .isInstanceOfSatisfying(InvalidJournalRequestException::class.java) {
                    assertThat(it.fieldErrors).containsExactly(JournalFieldError("Idempotency-Key", "invalid"))
                }
        }
        assertThatThrownBy { validator.validate(listOf("same", "same")) }
            .isInstanceOfSatisfying(InvalidJournalRequestException::class.java) {
                assertThat(it.fieldErrors).containsExactly(JournalFieldError("Idempotency-Key", "invalid"))
            }
    }

    @Test
    fun acceptsTheMaximumAsciiKeyLength() {
        val value = "a" + "b".repeat(127)

        assertThat(validator.validate(listOf(value))).isEqualTo(value)
    }
}
