package ro.upb.orarreader

import android.content.SharedPreferences
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.model.SubjectCatalog
import ro.upb.orarreader.model.canonicalizeSubjectCode
import java.text.Normalizer

object SubjectSubgroups {
    const val SHOW_ALL = 0

    data class Subject(
        val key: String,
        val label: String,
        val subgroupCount: Int,
    )

    fun detect(
        slots: List<ScheduleSlot>,
        catalog: SubjectCatalog,
    ): List<Subject> {
        val activities = slots
            .flatMap { it.activities }
            .filter { it.subgroupIndex > 0 && !it.optional && !it.facultative }

        return activities
            .groupBy(::keyFor)
            .map { (key, values) ->
                val first = values.first()
                val resolved = catalog.find(first.code)
                val fullName = resolved?.fullName?.takeIf { it.isNotBlank() } ?: first.subject
                val code = resolved?.code ?: first.code
                val label = if (!code.isNullOrBlank() && !fullName.equals(code, ignoreCase = true)) {
                    "$fullName ($code)"
                } else {
                    fullName
                }
                Subject(
                    key = key,
                    label = label,
                    subgroupCount = values.maxOf { it.subgroupIndex }.coerceAtLeast(1),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    fun readSelection(
        preferences: SharedPreferences,
        series: String,
        group: String,
        subjectKey: String,
        subgroupCount: Int,
    ): Int = preferences
        .getInt(preferenceKey(series, group, subjectKey), SHOW_ALL)
        .coerceIn(SHOW_ALL, subgroupCount)

    fun writeSelection(
        preferences: SharedPreferences,
        series: String,
        group: String,
        subjectKey: String,
        subgroup: Int,
    ) {
        preferences.edit()
            .putInt(preferenceKey(series, group, subjectKey), subgroup.coerceAtLeast(SHOW_ALL))
            .apply()
    }

    fun filter(
        preferences: SharedPreferences,
        series: String,
        group: String,
        slots: List<ScheduleSlot>,
    ): List<ScheduleSlot> {
        return slots.mapNotNull { slot ->
            val activities = slot.activities.filter { activity ->
                if (activity.subgroupIndex <= 0) return@filter true
                val selected = preferences.getInt(
                    preferenceKey(series, group, keyFor(activity)),
                    SHOW_ALL,
                )
                selected == SHOW_ALL || selected == activity.subgroupIndex
            }
            if (activities.isEmpty()) null else slot.copy(activities = activities)
        }
    }

    private fun keyFor(activity: ScheduleActivity): String {
        val code = activity.code?.let(::canonicalizeSubjectCode).orEmpty()
        if (code.isNotBlank()) return "CODE_$code"

        val normalized = Normalizer.normalize(activity.subject.uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^A-Z0-9]+"), "_")
            .trim('_')
        return "SUBJECT_$normalized"
    }

    private fun preferenceKey(series: String, group: String, subjectKey: String): String =
        "subject_subgroup_${series.uppercase()}_${group}_$subjectKey"
}
