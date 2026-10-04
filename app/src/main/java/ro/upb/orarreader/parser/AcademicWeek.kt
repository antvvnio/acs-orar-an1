package ro.upb.orarreader.parser

import ro.upb.orarreader.model.WeekParity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

object AcademicWeek {
    /** First teaching week of semester I, academic year 2026-2027. */
    val firstOddWeekMonday: LocalDate = LocalDate.of(2026, 9, 28)

    /**
     * Official Bucharest teaching weeks for semester I:
     * 12 weeks from 28 Sep to 18 Dec 2026, winter break, then 2 weeks from 11 to 22 Jan 2027.
     * Keeping the explicit teaching-week sequence also keeps odd/even parity correct across the break.
     */
    val teachingWeekMondays: List<LocalDate> = buildList {
        repeat(12) { add(LocalDate.of(2026, 9, 28).plusWeeks(it.toLong())) }
        repeat(2) { add(LocalDate.of(2027, 1, 11).plusWeeks(it.toLong())) }
    }

    val firstTeachingDate: LocalDate = teachingWeekMondays.first()
    val lastTeachingDate: LocalDate = teachingWeekMondays.last().plusDays(4)

    private val universityHolidays = setOf(
        LocalDate.of(2026, 11, 30),
        LocalDate.of(2026, 12, 1),
    )

    fun parityFor(date: LocalDate): WeekParity {
        val monday = mondayOf(date)
        val teachingIndex = teachingWeekMondays.indexOf(monday)
        if (teachingIndex >= 0) {
            return if (teachingIndex % 2 == 0) WeekParity.ODD else WeekParity.EVEN
        }

        // Safe fallback for dates outside the configured teaching periods.
        val offset = ChronoUnit.WEEKS.between(firstOddWeekMonday, monday)
        return if (Math.floorMod(offset, 2L) == 0L) WeekParity.ODD else WeekParity.EVEN
    }

    fun mondayOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun teachingWeekNumber(date: LocalDate): Int? {
        val index = teachingWeekMondays.indexOf(mondayOf(date))
        return if (index >= 0) index + 1 else null
    }

    fun closestTeachingWeekIndex(date: LocalDate): Int {
        val monday = mondayOf(date)
        return teachingWeekMondays.indices.minByOrNull { index ->
            abs(ChronoUnit.DAYS.between(monday, teachingWeekMondays[index]))
        } ?: 0
    }

    fun isTeachingDate(date: LocalDate): Boolean {
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return false
        if (date in universityHolidays) return false
        return mondayOf(date) in teachingWeekMondays
    }

    fun isUniversityHoliday(date: LocalDate): Boolean = date in universityHolidays

    fun labelFor(date: LocalDate): String {
        val monday = mondayOf(date)
        val friday = monday.plusDays(4)
        val parity = parityFor(date)
        val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ro-RO"))
        val parityLabel = if (parity == WeekParity.ODD) "IMPARĂ" else "PARĂ"
        return "Săptămâna $parityLabel • ${monday.format(formatter)} – ${friday.format(formatter)}"
    }
}
