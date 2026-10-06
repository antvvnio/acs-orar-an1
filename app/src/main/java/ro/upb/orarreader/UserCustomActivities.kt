package ro.upb.orarreader

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import ro.upb.orarreader.model.ActivityType
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.model.WeekParity
import java.util.UUID

object UserCustomActivities {
    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val subject: String,
        val type: ActivityType?,
        val day: String,
        val startHour: Int,
        val endHour: Int,
        val parity: WeekParity,
        val room: String = "",
    ) {
        fun toSlot(): ScheduleSlot = ScheduleSlot(
            day = day,
            startHour = startHour,
            endHour = endHour,
            parity = parity,
            activities = listOf(
                ScheduleActivity(
                    subject = subject,
                    code = null,
                    type = type,
                    room = room.takeIf { it.isNotBlank() },
                    subgroupIndex = 0,
                    customId = id,
                )
            ),
        )
    }

    fun read(
        preferences: SharedPreferences,
        series: String,
        groupNumber: String,
    ): List<Entry> {
        val raw = preferences.getString(key(series, groupNumber), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val subject = item.optString("subject").trim()
                    if (subject.isBlank()) continue
                    val start = item.optInt("startHour", 8).coerceIn(6, 23)
                    val end = item.optInt("endHour", start + 2).coerceIn(start + 1, 24)
                    val type = item.optString("type")
                        .takeIf { it.isNotBlank() }
                        ?.let { runCatching { ActivityType.valueOf(it) }.getOrNull() }
                    val parity = runCatching {
                        WeekParity.valueOf(item.optString("parity", WeekParity.BOTH.name))
                    }.getOrDefault(WeekParity.BOTH)

                    add(
                        Entry(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            subject = subject,
                            type = type,
                            day = item.optString("day", "LUNI").ifBlank { "LUNI" },
                            startHour = start,
                            endHour = end,
                            parity = parity,
                            room = item.optString("room"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsert(
        preferences: SharedPreferences,
        series: String,
        groupNumber: String,
        entry: Entry,
    ) {
        val entries = read(preferences, series, groupNumber).toMutableList()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) entries[index] = entry else entries += entry
        write(preferences, series, groupNumber, entries)
    }

    fun delete(
        preferences: SharedPreferences,
        series: String,
        groupNumber: String,
        id: String,
    ) {
        write(
            preferences,
            series,
            groupNumber,
            read(preferences, series, groupNumber).filterNot { it.id == id },
        )
    }

    fun slots(
        preferences: SharedPreferences,
        series: String,
        groupNumber: String,
    ): List<ScheduleSlot> = read(preferences, series, groupNumber).map(Entry::toSlot)

    private fun write(
        preferences: SharedPreferences,
        series: String,
        groupNumber: String,
        entries: List<Entry>,
    ) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("subject", entry.subject)
                    .put("type", entry.type?.name ?: "")
                    .put("day", entry.day)
                    .put("startHour", entry.startHour)
                    .put("endHour", entry.endHour)
                    .put("parity", entry.parity.name)
                    .put("room", entry.room)
            )
        }
        preferences.edit().putString(key(series, groupNumber), array.toString()).apply()
    }

    private fun key(series: String, groupNumber: String): String =
        "custom_activities_${series.uppercase()}_${groupNumber.trim()}"
}
