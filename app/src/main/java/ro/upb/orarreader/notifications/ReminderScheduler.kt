package ro.upb.orarreader.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.model.WeekParity
import ro.upb.orarreader.model.canonicalizeSubjectCode
import ro.upb.orarreader.parser.AcademicWeek
import ro.upb.orarreader.parser.BundledScheduleReader
import ro.upb.orarreader.parser.ScheduleParser
import ro.upb.orarreader.parser.SubjectCatalogParser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

object ReminderScheduler {
    const val CHANNEL_ID = "class_reminders"
    const val PREFS_NAME = "upb_orar"
    const val KEY_ENABLED = "reminders_enabled"
    const val KEY_MINUTES = "reminder_minutes"
    const val KEY_ALARM_IDS = "reminder_alarm_ids"
    const val KEY_CURRENT_SERIES = "current_series"
    const val EXTRA_DAY = "open_day"

    private const val ACTION_REMINDER = "ro.upb.orarreader.CLASS_REMINDER"
    private const val HORIZON_DAYS = 35L
    private val executor = Executors.newSingleThreadExecutor()

    fun isNotificationPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Mementouri ACS Orar",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notificări înainte de cursuri, laboratoare și seminare"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun rescheduleAsync(context: Context) {
        val app = context.applicationContext
        executor.execute { runCatching { reschedule(app) } }
    }

    @Synchronized
    fun reschedule(context: Context) {
        createChannel(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        cancelAll(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return
        if (!isNotificationPermissionGranted(context)) return

        val series = prefs.getString(KEY_CURRENT_SERIES, null) ?: return
        val groupNumber = prefs.getString("group_$series", null) ?: return
        val minutes = prefs.getInt(KEY_MINUTES, 15).coerceIn(0, 120)
        val optionals = prefs.getStringSet("optionals_$series", emptySet())
            ?.map(::canonicalizeSubjectCode)
            ?.toSet()
            .orEmpty()

        val sheet = readSheet(context, series)
        val groups = ScheduleParser.detectGroups(sheet)
        val group = groups.firstOrNull { it.number == groupNumber } ?: return
        val catalog = SubjectCatalogParser.parse(sheet)
        val slots = ScheduleParser.parseForGroup(sheet, group, optionals, catalog)

        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val scheduledIds = linkedSetOf<String>()

        for (offset in 0L..HORIZON_DAYS) {
            val date = today.plusDays(offset)
            if (!AcademicWeek.isTeachingDate(date)) continue
            val day = dayName(date.dayOfWeek) ?: continue
            val parity = AcademicWeek.parityFor(date)
            val daySlots = slots.filter { slot ->
                slot.day == day && (slot.parity == WeekParity.BOTH || slot.parity == parity)
            }

            for (slot in daySlots) {
                val startDateTime = LocalDateTime.of(date, java.time.LocalTime.of(slot.startHour.coerceAtMost(23), 0))
                val triggerMillis = startDateTime.minusMinutes(minutes.toLong())
                    .atZone(zone)
                    .toInstant()
                    .toEpochMilli()
                if (triggerMillis <= now + 2_000L) continue

                val requestCode = requestCode(series, groupNumber, date, slot)
                val intent = Intent(context, ReminderReceiver::class.java).apply {
                    action = ACTION_REMINDER
                    putExtra("title", buildTitle(slot.activities, minutes))
                    putExtra("text", buildText(slot))
                    putExtra("group", group.name)
                    putExtra("start_hour", slot.startHour)
                    putExtra(EXTRA_DAY, slot.day)
                    putExtra("notification_id", requestCode)
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    requestCode,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pending)
                scheduledIds += requestCode.toString()
            }
        }
        prefs.edit().putStringSet(KEY_ALARM_IDS, scheduledIds).apply()
    }

    @Synchronized
    fun cancelAll(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val manager = context.getSystemService(AlarmManager::class.java)
        for (idRaw in prefs.getStringSet(KEY_ALARM_IDS, emptySet()).orEmpty()) {
            val id = idRaw.toIntOrNull() ?: continue
            val pending = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMINDER),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pending != null) {
                manager.cancel(pending)
                pending.cancel()
            }
        }
        prefs.edit().remove(KEY_ALARM_IDS).apply()
    }

    private fun readSheet(context: Context, series: String): ro.upb.orarreader.model.GridSheet {
        return BundledScheduleReader.read(context.assets, series)
    }

    private fun requestCode(series: String, group: String, date: LocalDate, slot: ScheduleSlot): Int {
        val key = "$series|$group|$date|${slot.startHour}|${slot.endHour}|${slot.activities.joinToString { it.code ?: it.subject }}"
        return key.hashCode() and 0x7fffffff
    }

    private fun buildTitle(activities: List<ScheduleActivity>, minutes: Int): String {
        val names = activities.map { it.subject }.distinct().take(2)
        val subject = names.joinToString(" / ").ifBlank { "activitate" }
        return when (minutes) {
            0 -> "Acum începe $subject"
            1 -> "În 1 minut ai $subject"
            else -> "În $minutes minute ai $subject"
        }
    }

    private fun buildText(slot: ScheduleSlot): String {
        val rooms = slot.activities.mapNotNull { it.room }.distinct()
        val types = slot.activities.mapNotNull { it.type?.displayName }.distinct()
        return buildString {
            append("%02d:00".format(slot.startHour))
            if (rooms.isNotEmpty()) append(" · Sala ${rooms.joinToString(" / ")}")
            if (types.isNotEmpty()) append(" · ${types.joinToString(" / ")}")
        }
    }


    private fun dayName(day: DayOfWeek): String? = when (day) {
        DayOfWeek.MONDAY -> "LUNI"
        DayOfWeek.TUESDAY -> "MARȚI"
        DayOfWeek.WEDNESDAY -> "MIERCURI"
        DayOfWeek.THURSDAY -> "JOI"
        DayOfWeek.FRIDAY -> "VINERI"
        else -> null
    }
}
