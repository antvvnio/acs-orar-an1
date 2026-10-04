package ro.upb.orarreader

import android.content.SharedPreferences
import ro.upb.orarreader.model.ActivityType
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.model.SubjectCatalog
import ro.upb.orarreader.model.WeekParity
import ro.upb.orarreader.model.canonicalizeSubjectCode

object CustomOptionalSeminars {
    data class Config(
        val enabled: Boolean = false,
        val day: String = "LUNI",
        val startHour: Int = 8,
        val endHour: Int = 10,
        val parity: WeekParity = WeekParity.BOTH,
        val room: String = "",
    )

    fun read(
        preferences: SharedPreferences,
        series: String,
        code: String,
    ): Config {
        val prefix = prefix(series, code)
        val parity = runCatching {
            WeekParity.valueOf(preferences.getString("${prefix}parity", WeekParity.BOTH.name).orEmpty())
        }.getOrDefault(WeekParity.BOTH)
        val start = preferences.getInt("${prefix}start", 8).coerceIn(6, 23)
        val end = preferences.getInt("${prefix}end", start + 2).coerceIn(start + 1, 24)

        return Config(
            enabled = preferences.getBoolean("${prefix}enabled", false),
            day = preferences.getString("${prefix}day", "LUNI").orEmpty().ifBlank { "LUNI" },
            startHour = start,
            endHour = end,
            parity = parity,
            room = preferences.getString("${prefix}room", "").orEmpty(),
        )
    }

    fun write(
        preferences: SharedPreferences,
        series: String,
        code: String,
        config: Config,
    ) {
        val prefix = prefix(series, code)
        preferences.edit()
            .putBoolean("${prefix}enabled", config.enabled)
            .putString("${prefix}day", config.day)
            .putInt("${prefix}start", config.startHour)
            .putInt("${prefix}end", config.endHour)
            .putString("${prefix}parity", config.parity.name)
            .putString("${prefix}room", config.room)
            .apply()
    }

    fun slots(
        preferences: SharedPreferences,
        series: String,
        selectedCodes: Set<String>,
        allowedTypes: Map<String, ActivityType>,
        catalog: SubjectCatalog,
    ): List<ScheduleSlot> {
        return selectedCodes
            .map(::canonicalizeSubjectCode)
            .distinct()
            .filter { it in allowedTypes }
            .mapNotNull { canonical ->
                val subject = catalog.subjects[canonical] ?: return@mapNotNull null
                val activityType = allowedTypes[canonical] ?: return@mapNotNull null
                val config = read(preferences, series, canonical)
                if (!config.enabled) return@mapNotNull null

                ScheduleSlot(
                    day = config.day,
                    startHour = config.startHour,
                    endHour = config.endHour,
                    parity = config.parity,
                    activities = listOf(
                        ScheduleActivity(
                            subject = subject.fullName,
                            code = subject.code,
                            type = activityType,
                            room = config.room.takeIf { it.isNotBlank() },
                            subgroupIndex = 0,
                            optional = true,
                        )
                    ),
                )
            }
    }

    fun mergeSlots(base: List<ScheduleSlot>, extra: List<ScheduleSlot>): List<ScheduleSlot> {
        if (extra.isEmpty()) return base
        return (base + extra)
            .groupBy { SlotKey(it.day, it.startHour, it.endHour, it.parity) }
            .map { (key, slots) ->
                ScheduleSlot(
                    day = key.day,
                    startHour = key.startHour,
                    endHour = key.endHour,
                    parity = key.parity,
                    activities = slots.flatMap { it.activities }.distinct(),
                )
            }
    }

    private fun prefix(series: String, code: String): String =
        "manual_seminar_${series.uppercase()}_${canonicalizeSubjectCode(code)}_"

    private data class SlotKey(
        val day: String,
        val startHour: Int,
        val endHour: Int,
        val parity: WeekParity,
    )
}
