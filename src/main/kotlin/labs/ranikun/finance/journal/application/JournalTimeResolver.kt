package labs.ranikun.finance.journal.application

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField
import java.util.Locale
import java.time.Instant

data class ResolvedJournalTime(
    val occurredAt: Instant,
    val occurredLocalAt: LocalDateTime,
    val occurredTimeZone: String,
)

class JournalTimeResolver {

    fun resolve(occurredAt: String, timeZone: String): ResolvedJournalTime {
        val localDateTime = try {
            LocalDateTime.parse(occurredAt, LOCAL_DATE_TIME_FORMATTER)
        } catch (exception: DateTimeException) {
            throw IllegalArgumentException("Invalid occurredAt", exception)
        }

        if (timeZone !in ZoneId.getAvailableZoneIds()) {
            throw IllegalArgumentException("Invalid IANA time zone")
        }

        val zoneId = ZoneId.of(timeZone)
        val validOffsets = zoneId.rules.getValidOffsets(localDateTime)
        val offset = when (validOffsets.size) {
            1 -> validOffsets.single()
            0 -> throw IllegalArgumentException("DST gap is not a valid local time")
            else -> throw IllegalArgumentException("DST overlap is not a valid local time")
        }

        return ResolvedJournalTime(
            occurredAt = localDateTime.toInstant(offset),
            occurredLocalAt = localDateTime,
            occurredTimeZone = timeZone,
        )
    }

    companion object {
        private val LOCAL_DATE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatterBuilder()
            .parseCaseSensitive()
            .appendPattern("uuuu-MM-dd'T'HH:mm")
            .optionalStart()
            .appendPattern(":ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 3, true)
            .optionalEnd()
            .optionalEnd()
            .toFormatter(Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT)
    }
}
