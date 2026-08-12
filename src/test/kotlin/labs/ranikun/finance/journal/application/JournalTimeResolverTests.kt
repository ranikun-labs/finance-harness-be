package labs.ranikun.finance.journal.application

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class JournalTimeResolverTests {

    private val resolver = JournalTimeResolver()

    @Test
    fun resolvesMinuteSecondsAndMillisecondsUsingTheProvidedIanaZone() {
        val resolved = resolver.resolve("2026-08-12T14:30:15.123", "Asia/Seoul")

        assertThat(resolved.occurredLocalAt.toString()).isEqualTo("2026-08-12T14:30:15.123")
        assertThat(resolved.occurredTimeZone).isEqualTo("Asia/Seoul")
        assertThat(resolved.occurredAt).isEqualTo(Instant.parse("2026-08-12T05:30:15.123Z"))
    }

    @Test
    fun resolvesMinutePrecisionWithoutRelyingOnJvmDefaultZone() {
        val resolved = resolver.resolve("2026-08-12T14:30", "Asia/Seoul")

        assertThat(resolved.occurredAt).isEqualTo(Instant.parse("2026-08-12T05:30:00Z"))
    }

    @Test
    fun rejectsOffsetAndZLocalDateTimeInputs() {
        assertThatThrownBy { resolver.resolve("2026-08-12T14:30Z", "Asia/Seoul") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { resolver.resolve("2026-08-12T14:30+09:00", "Asia/Seoul") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsInvalidDateAndExcessiveFractionPrecision() {
        assertThatThrownBy { resolver.resolve("2026-02-30T14:30", "Asia/Seoul") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { resolver.resolve("2026-08-12T14:30:15.1234", "Asia/Seoul") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsUnknownAndNumericOffsetZones() {
        assertThatThrownBy { resolver.resolve("2026-08-12T14:30", "Not/AZone") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { resolver.resolve("2026-08-12T14:30", "+09:00") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsDstGapInsteadOfSilentlyAdjustingTheLocalTime() {
        assertThatThrownBy { resolver.resolve("2026-03-08T02:30", "America/New_York") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun rejectsDstOverlapInsteadOfSilentlyChoosingAnOffset() {
        assertThatThrownBy { resolver.resolve("2026-11-01T01:30", "America/New_York") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
