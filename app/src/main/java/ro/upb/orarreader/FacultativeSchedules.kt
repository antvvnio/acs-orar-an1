package ro.upb.orarreader

import android.content.SharedPreferences
import ro.upb.orarreader.model.ActivityType
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.parser.FacultativeScheduleParser

object FacultativeSchedules {
    const val PSYCHOLOGY_KEY = "PSIHOLOGIA_EDUCATIEI"
    const val FRENCH_KEY = "FRANCEZA"

    private const val ALL_COURSES = "ALL"

    fun isPsychologyEnabled(preferences: SharedPreferences, series: String): Boolean =
        preferences.getBoolean("facultative_${series}_psychology", false)

    fun isFrenchEnabled(preferences: SharedPreferences, series: String): Boolean =
        preferences.getBoolean("facultative_${series}_french", false)

    fun setEnabled(
        preferences: SharedPreferences,
        series: String,
        psychology: Boolean,
        french: Boolean,
    ) {
        preferences.edit()
            .putBoolean("facultative_${series}_psychology", psychology)
            .putBoolean("facultative_${series}_french", french)
            .apply()
    }

    fun psychologyCourseSelection(preferences: SharedPreferences, series: String): String =
        preferences.getString("facultative_${series}_psychology_course", ALL_COURSES)
            .orEmpty()
            .ifBlank { ALL_COURSES }

    fun setPsychologyCourseSelection(
        preferences: SharedPreferences,
        series: String,
        selection: String,
    ) {
        preferences.edit()
            .putString("facultative_${series}_psychology_course", selection.ifBlank { ALL_COURSES })
            .apply()
    }

    fun courseKey(slot: ScheduleSlot): String =
        "${slot.day}|${slot.startHour}|${slot.endHour}"

    fun psychologyCourseSlots(
        preferences: SharedPreferences,
        series: String,
        info: FacultativeScheduleParser.Info,
    ): List<ScheduleSlot> {
        if (!isPsychologyEnabled(preferences, series)) return emptyList()
        val selection = psychologyCourseSelection(preferences, series)
        if (selection == ALL_COURSES) return info.psychologyCourses
        return info.psychologyCourses.filter { courseKey(it) == selection }
    }

    fun manualSlots(
        preferences: SharedPreferences,
        series: String,
        info: FacultativeScheduleParser.Info,
    ): List<ScheduleSlot> {
        val result = mutableListOf<ScheduleSlot>()

        if (isPsychologyEnabled(preferences, series) && info.psychologySeminarManual) {
            customSlot(
                preferences = preferences,
                series = series,
                key = PSYCHOLOGY_KEY,
                subject = "Psihologia educației",
            )?.let(result::add)
        }

        if (isFrenchEnabled(preferences, series) && info.frenchSeminarManual) {
            customSlot(
                preferences = preferences,
                series = series,
                key = FRENCH_KEY,
                subject = "Franceză",
            )?.let(result::add)
        }

        return result
    }

    private fun customSlot(
        preferences: SharedPreferences,
        series: String,
        key: String,
        subject: String,
    ): ScheduleSlot? {
        val config = CustomOptionalSeminars.read(preferences, series, key)
        if (!config.enabled) return null
        return ScheduleSlot(
            day = config.day,
            startHour = config.startHour,
            endHour = config.endHour,
            parity = config.parity,
            activities = listOf(
                ScheduleActivity(
                    subject = subject,
                    code = null,
                    type = ActivityType.SEMINAR,
                    room = config.room.takeIf { it.isNotBlank() },
                    subgroupIndex = 0,
                    facultative = true,
                )
            ),
        )
    }
}
