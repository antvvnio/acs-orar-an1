package ro.upb.orarreader

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.CircularProgressIndicator
import ro.upb.orarreader.model.ActivityType
import ro.upb.orarreader.model.GridSheet
import ro.upb.orarreader.model.GroupInfo
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.model.SubjectCatalog
import ro.upb.orarreader.model.WeekParity
import ro.upb.orarreader.model.canonicalizeSubjectCode
import ro.upb.orarreader.parser.AcademicWeek
import ro.upb.orarreader.parser.BundledScheduleReader
import ro.upb.orarreader.parser.ScheduleParser
import ro.upb.orarreader.parser.SubjectCatalogParser
import ro.upb.orarreader.notifications.ReminderScheduler
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var setupScroll: NestedScrollView
    private lateinit var scheduleScroll: NestedScrollView
    private lateinit var seriesDropdown: AutoCompleteTextView
    private lateinit var groupDropdown: AutoCompleteTextView
    private lateinit var optionalCard: MaterialCardView
    private lateinit var optionalList: LinearLayout
    private lateinit var sourceText: TextView
    private lateinit var saveConfigurationButton: MaterialButton
    private lateinit var setupProgress: CircularProgressIndicator
    private lateinit var mainProgress: CircularProgressIndicator

    private lateinit var weekPositionText: TextView
    private lateinit var weekParityText: TextView
    private lateinit var weekRangeText: TextView
    private lateinit var todayText: TextView
    private lateinit var previousWeekButton: MaterialButton
    private lateinit var nextWeekButton: MaterialButton
    private lateinit var profileBadge: TextView
    private lateinit var dayChipGroup: ChipGroup
    private lateinit var dayTitleText: TextView
    private lateinit var daySummaryText: TextView
    private lateinit var scheduleContainer: LinearLayout
    private lateinit var emptyCard: MaterialCardView
    private lateinit var emptyText: TextView
    private lateinit var dataSourceFooter: TextView

    private val preferences by lazy { getSharedPreferences("upb_orar", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val loadGeneration = AtomicInteger(0)

    private val seriesOrder = listOf("AA", "AB", "AC", "CA", "CB", "CC", "CD")
    private val days = listOf("LUNI", "MARȚI", "MIERCURI", "JOI", "VINERI")
    private val dayShort = mapOf(
        "LUNI" to "Lun",
        "MARȚI" to "Mar",
        "MIERCURI" to "Mie",
        "JOI" to "Joi",
        "VINERI" to "Vin",
    )

    private var setupData: LoadedSchedule? = null
    private var currentData: LoadedSchedule? = null
    private var currentGroup: GroupInfo? = null
    private var currentSlots: List<ScheduleSlot> = emptyList()
    private var currentOptionals: Set<String> = emptySet()
    private var selectedDay: String = "LUNI"
    private var requestedDay: String? = null
    private var anchorTeachingWeekIndex: Int = AcademicWeek.closestTeachingWeekIndex(LocalDate.now())
    private var selectedTeachingWeekIndex: Int = anchorTeachingWeekIndex

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        bindViews()
        configureToolbar()
        configureSeriesDropdown()
        configureDayChips()
        configureWeekNavigation()
        requestedDay = intent.getStringExtra(ReminderScheduler.EXTRA_DAY)
        ReminderScheduler.createChannel(this)

        saveConfigurationButton.setOnClickListener { saveConfiguration() }

        val savedSeries = preferences.getString(KEY_CURRENT_SERIES, null)
        val savedGroup = savedSeries?.let { preferences.getString(groupKey(it), null) }
        if (savedSeries in seriesOrder && !savedGroup.isNullOrBlank()) {
            showSavedSchedule(savedSeries!!)
        } else {
            showSetup(savedSeries?.takeIf { it in seriesOrder } ?: "AA")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdownNow()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        setupScroll = findViewById(R.id.setupScroll)
        scheduleScroll = findViewById(R.id.scheduleScroll)
        seriesDropdown = findViewById(R.id.seriesDropdown)
        groupDropdown = findViewById(R.id.groupDropdown)
        optionalCard = findViewById(R.id.optionalCard)
        optionalList = findViewById(R.id.optionalList)
        sourceText = findViewById(R.id.sourceText)
        saveConfigurationButton = findViewById(R.id.saveConfigurationButton)
        setupProgress = findViewById(R.id.setupProgress)
        mainProgress = findViewById(R.id.mainProgress)
        weekPositionText = findViewById(R.id.weekPositionText)
        weekParityText = findViewById(R.id.weekParityText)
        weekRangeText = findViewById(R.id.weekRangeText)
        todayText = findViewById(R.id.todayText)
        previousWeekButton = findViewById(R.id.previousWeekButton)
        nextWeekButton = findViewById(R.id.nextWeekButton)
        profileBadge = findViewById(R.id.profileBadge)
        dayChipGroup = findViewById(R.id.dayChipGroup)
        dayTitleText = findViewById(R.id.dayTitleText)
        daySummaryText = findViewById(R.id.daySummaryText)
        scheduleContainer = findViewById(R.id.scheduleContainer)
        emptyCard = findViewById(R.id.emptyCard)
        emptyText = findViewById(R.id.emptyText)
        dataSourceFooter = findViewById(R.id.dataSourceFooter)
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.mainRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun configureToolbar() {
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    val series = currentData?.series
                        ?: preferences.getString(KEY_CURRENT_SERIES, "AA").orEmpty().ifBlank { "AA" }
                    showSetup(series)
                    true
                }
                R.id.action_map -> {
                    startActivity(Intent(this, CampusMapActivity::class.java))
                    true
                }
                R.id.action_notifications -> {
                    startActivity(Intent(this, ReminderSettingsActivity::class.java))
                    true
                }
                R.id.action_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    private fun configureSeriesDropdown() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, seriesOrder)
        seriesDropdown.setAdapter(adapter)
        seriesDropdown.setOnItemClickListener { _, _, position, _ ->
            val series = adapter.getItem(position) ?: return@setOnItemClickListener
            loadSetupSeries(series)
        }
    }

    private fun configureDayChips() {
        dayChipGroup.removeAllViews()
        days.forEach { day ->
            val chip = Chip(this).apply {
                id = View.generateViewId()
                isCheckable = true
                isClickable = true
                chipMinHeight = dp(42).toFloat()
                chipCornerRadius = dp(15).toFloat()
                chipStrokeWidth = dp(1).toFloat()
                chipStrokeColor = ColorStateList.valueOf(color(R.color.outline))
                chipBackgroundColor = ContextCompat.getColorStateList(context, R.color.day_chip_background)
                setTextColor(ContextCompat.getColorStateList(context, R.color.day_chip_text))
                setCheckedIconVisible(false)
                setEnsureMinTouchTargetSize(false)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                tag = day
                setOnClickListener {
                    selectedDay = day
                    renderSelectedDay()
                }
            }
            dayChipGroup.addView(chip)
        }
        refreshDayChipLabels()
    }

    private fun configureWeekNavigation() {
        previousWeekButton.setOnClickListener { moveTeachingWeek(-1) }
        nextWeekButton.setOnClickListener { moveTeachingWeek(1) }
        updateWeekNavigationButtons()
    }

    private fun moveTeachingWeek(delta: Int) {
        val minIndex = maxOf(0, anchorTeachingWeekIndex - WEEK_NAVIGATION_RADIUS)
        val maxIndex = minOf(AcademicWeek.teachingWeekMondays.lastIndex, anchorTeachingWeekIndex + WEEK_NAVIGATION_RADIUS)
        val target = (selectedTeachingWeekIndex + delta).coerceIn(minIndex, maxIndex)
        if (target == selectedTeachingWeekIndex) return

        selectedTeachingWeekIndex = target
        updateWeekHeader()
        refreshDayChipLabels()
        updateWeekNavigationButtons()
        renderSelectedDay()
    }

    private fun selectedWeekMonday(): LocalDate =
        AcademicWeek.teachingWeekMondays[selectedTeachingWeekIndex]

    private fun refreshDayChipLabels() {
        val today = LocalDate.now()
        val selectedMonday = selectedWeekMonday()
        val todayName = dayName(today.dayOfWeek)
        val selectedIsCurrentCalendarWeek = selectedMonday == AcademicWeek.mondayOf(today)

        for (index in 0 until dayChipGroup.childCount) {
            val chip = dayChipGroup.getChildAt(index) as? Chip ?: continue
            val day = chip.tag as? String ?: continue
            chip.text = buildString {
                append(dayShort.getValue(day))
                if (selectedIsCurrentCalendarWeek && day == todayName) append(" · azi")
            }
        }
    }

    private fun updateWeekNavigationButtons() {
        val minIndex = maxOf(0, anchorTeachingWeekIndex - WEEK_NAVIGATION_RADIUS)
        val maxIndex = minOf(AcademicWeek.teachingWeekMondays.lastIndex, anchorTeachingWeekIndex + WEEK_NAVIGATION_RADIUS)
        previousWeekButton.isEnabled = selectedTeachingWeekIndex > minIndex
        nextWeekButton.isEnabled = selectedTeachingWeekIndex < maxIndex
        previousWeekButton.alpha = if (previousWeekButton.isEnabled) 1f else 0.38f
        nextWeekButton.alpha = if (nextWeekButton.isEnabled) 1f else 0.38f
    }

    private fun showSetup(series: String) {
        setupScroll.visibility = View.VISIBLE
        scheduleScroll.visibility = View.GONE
        mainProgress.visibility = View.GONE
        toolbar.title = "Configurare"
        toolbar.subtitle = "ACS · Anul I"
        if (currentData != null) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back_24)
            toolbar.setNavigationOnClickListener {
                val currentSeries = currentData?.series ?: series
                showSavedSchedule(currentSeries)
            }
        } else {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
        }
        val normalizedSeries = series.takeIf { it in seriesOrder } ?: "AA"
        seriesDropdown.setText(normalizedSeries, false)
        loadSetupSeries(normalizedSeries)
    }

    private fun loadSetupSeries(series: String) {
        val generation = loadGeneration.incrementAndGet()
        setupProgress.visibility = View.VISIBLE
        saveConfigurationButton.isEnabled = false
        groupDropdown.isEnabled = false
        optionalList.removeAllViews()
        sourceText.text = "Se încarcă seria $series…"

        loadSeriesAsync(series, generation) { data ->
            setupData = data
            setupProgress.visibility = View.GONE
            saveConfigurationButton.isEnabled = true
            groupDropdown.isEnabled = true

            val groupNumbers = data.groups.map { it.number }.distinct()
            val groupAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, groupNumbers)
            groupDropdown.setAdapter(groupAdapter)
            groupDropdown.setOnClickListener { groupDropdown.showDropDown() }

            val savedGroup = preferences.getString(groupKey(series), null)
                ?.takeIf { it in groupNumbers }
                ?: groupNumbers.firstOrNull().orEmpty()
            groupDropdown.setText(savedGroup, false)

            val savedOptionals = preferences.getStringSet(optionalKey(series), emptySet())
                ?.map(::canonicalizeSubjectCode)
                ?.toSet()
                .orEmpty()
            populateOptionalChecks(data.catalog, savedOptionals)
            sourceText.text = data.sourceLabel
        }
    }

    private fun populateOptionalChecks(catalog: SubjectCatalog, selected: Set<String>) {
        optionalList.removeAllViews()
        val optionals = catalog.optionalSubjects.sortedWith(compareBy({ optionalCategoryRank(it.code) }, { it.code.lowercase() }))
        optionalCard.visibility = if (optionals.isEmpty()) View.GONE else View.VISIBLE

        for (subject in optionals) {
            val check = MaterialCheckBox(this).apply {
                tag = subject.canonicalCode
                text = "${subject.fullName} (${subject.code})"
                textSize = 14f
                setTextColor(color(R.color.text_primary))
                isChecked = subject.canonicalCode in selected
                buttonTintList = ColorStateList.valueOf(color(R.color.accent))
                minHeight = dp(48)
                setPadding(dp(2), dp(5), dp(2), dp(5))
            }
            optionalList.addView(check)
        }
    }

    private fun optionalCategoryRank(code: String): Int {
        return when (canonicalizeSubjectCode(code)) {
            "IA1", "GAC", "IA2" -> 0
            else -> 1
        }
    }

    private fun saveConfiguration() {
        val data = setupData ?: return
        val selectedSeries = seriesDropdown.text.toString().trim().uppercase()
        if (selectedSeries != data.series) {
            Toast.makeText(this, "Așteaptă să se încarce seria selectată.", Toast.LENGTH_SHORT).show()
            return
        }

        val groupNumber = groupDropdown.text.toString().trim()
        val group = data.groups.firstOrNull { it.number == groupNumber }
        if (group == null) {
            Toast.makeText(this, "Alege o grupă validă.", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedOptionals = checkedOptionalCodes()
        preferences.edit()
            .putString(KEY_CURRENT_SERIES, selectedSeries)
            .putString(groupKey(selectedSeries), groupNumber)
            .putStringSet(optionalKey(selectedSeries), selectedOptionals)
            .apply()

        ReminderScheduler.rescheduleAsync(this)
        showSavedSchedule(selectedSeries)
    }

    private fun checkedOptionalCodes(): Set<String> {
        val result = linkedSetOf<String>()
        for (index in 0 until optionalList.childCount) {
            val view = optionalList.getChildAt(index)
            if (view is MaterialCheckBox && view.isChecked) {
                (view.tag as? String)?.let(result::add)
            }
        }
        return result
    }

    private fun showSavedSchedule(series: String) {
        val generation = loadGeneration.incrementAndGet()
        setupScroll.visibility = View.GONE
        scheduleScroll.visibility = View.GONE
        mainProgress.visibility = View.VISIBLE
        toolbar.title = "ACS Orar"
        toolbar.subtitle = "Se încarcă…"
        toolbar.navigationIcon = null
        toolbar.setNavigationOnClickListener(null)

        loadSeriesAsync(series, generation) { data ->
            val groupNumber = preferences.getString(groupKey(series), null)
            val group = data.groups.firstOrNull { it.number == groupNumber }
            if (group == null) {
                Toast.makeText(this, "Grupa salvată nu mai există în orar.", Toast.LENGTH_LONG).show()
                showSetup(series)
                return@loadSeriesAsync
            }

            val optionals = preferences.getStringSet(optionalKey(series), emptySet())
                ?.map(::canonicalizeSubjectCode)
                ?.filterTo(linkedSetOf()) { it in data.catalog.optionalCodes }
                .orEmpty()

            executor.execute {
                val slots = ScheduleParser.parseForGroup(data.sheet, group, optionals, data.catalog)
                runOnUiThread {
                    if (generation != loadGeneration.get() || isFinishing) return@runOnUiThread
                    currentData = data
                    currentGroup = group
                    currentOptionals = optionals
                    currentSlots = slots
                    mainProgress.visibility = View.GONE
                    scheduleScroll.visibility = View.VISIBLE
                    toolbar.title = "ACS Orar"
                    toolbar.subtitle = group.name
                    profileBadge.text = group.name
                    anchorTeachingWeekIndex = AcademicWeek.closestTeachingWeekIndex(LocalDate.now())
                    selectedTeachingWeekIndex = anchorTeachingWeekIndex
                    updateWeekHeader()
                    refreshDayChipLabels()
                    updateWeekNavigationButtons()
                    val targetDay = requestedDay?.takeIf { it in days }
                    if (targetDay != null) {
                        selectedDay = targetDay
                        setCheckedDayChip(targetDay)
                        requestedDay = null
                    } else {
                        selectDefaultDay()
                    }
                    renderSelectedDay()
                    dataSourceFooter.text = buildSourceFooter(data, optionals)
                    ReminderScheduler.rescheduleAsync(this)
                }
            }
        }
    }

    private fun updateWeekHeader() {
        val today = LocalDate.now()
        val romanian = Locale.forLanguageTag("ro-RO")
        val monday = selectedWeekMonday()
        val friday = monday.plusDays(4)
        val parity = AcademicWeek.parityFor(monday)
        val teachingWeekNumber = selectedTeachingWeekIndex + 1
        val relativeOffset = selectedTeachingWeekIndex - anchorTeachingWeekIndex

        weekPositionText.text = when (relativeOffset) {
            0 -> "SĂPTĂMÂNA $teachingWeekNumber · CURENTĂ"
            1 -> "SĂPTĂMÂNA $teachingWeekNumber · URMĂTOAREA"
            2 -> "SĂPTĂMÂNA $teachingWeekNumber · +2"
            -1 -> "SĂPTĂMÂNA $teachingWeekNumber · TRECUTĂ"
            -2 -> "SĂPTĂMÂNA $teachingWeekNumber · −2"
            else -> "SĂPTĂMÂNA $teachingWeekNumber"
        }
        weekParityText.text = if (parity == WeekParity.ODD) "Săptămână impară" else "Săptămână pară"
        val formatter = DateTimeFormatter.ofPattern("d MMM", romanian)
        weekRangeText.text = "${monday.format(formatter)} – ${friday.format(formatter)}"

        if (monday == AcademicWeek.mondayOf(today)) {
            val currentDay = today.dayOfWeek.getDisplayName(TextStyle.FULL, romanian)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(romanian) else it.toString() }
            val currentDate = today.format(DateTimeFormatter.ofPattern("d MMMM", romanian))
            todayText.text = "Azi · $currentDay, $currentDate"
        } else {
            val direction = if (relativeOffset > 0) "înainte" else "în urmă"
            val distance = kotlin.math.abs(relativeOffset)
            todayText.text = "Previzualizare · $distance ${if (distance == 1) "săptămână" else "săptămâni"} $direction"
        }
    }

    private fun selectDefaultDay() {
        val today = LocalDate.now()
        val todayName = dayName(today.dayOfWeek)
        selectedDay = if (selectedWeekMonday() == AcademicWeek.mondayOf(today) && todayName != null && todayName in days) {
            todayName!!
        } else {
            "LUNI"
        }
        setCheckedDayChip(selectedDay)
    }

    private fun setCheckedDayChip(day: String) {
        for (index in 0 until dayChipGroup.childCount) {
            val chip = dayChipGroup.getChildAt(index) as? Chip ?: continue
            chip.isChecked = chip.tag == day
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val day = intent.getStringExtra(ReminderScheduler.EXTRA_DAY)?.takeIf { it in days } ?: return
        requestedDay = day
        if (scheduleScroll.visibility == View.VISIBLE && currentData != null) {
            anchorTeachingWeekIndex = AcademicWeek.closestTeachingWeekIndex(LocalDate.now())
            selectedTeachingWeekIndex = anchorTeachingWeekIndex
            selectedDay = day
            updateWeekHeader()
            refreshDayChipLabels()
            updateWeekNavigationButtons()
            setCheckedDayChip(day)
            requestedDay = null
            renderSelectedDay()
        }
    }

    private fun renderSelectedDay() {
        val data = currentData ?: return
        val group = currentGroup ?: return
        val dayIndex = days.indexOf(selectedDay).coerceAtLeast(0)
        val date = selectedWeekMonday().plusDays(dayIndex.toLong())
        val parity = AcademicWeek.parityFor(date)
        val daySlots = if (AcademicWeek.isUniversityHoliday(date)) {
            emptyList()
        } else {
            currentSlots
                .filter { it.day == selectedDay && (it.parity == WeekParity.BOTH || it.parity == parity) }
                .sortedWith(compareBy<ScheduleSlot> { it.startHour }.thenBy { it.endHour })
        }

        val dayLabel = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ro-RO"))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.forLanguageTag("ro-RO")) else it.toString() }
        val dateLabel = date.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ro-RO")))
        dayTitleText.text = "$dayLabel, $dateLabel"
        daySummaryText.text = buildDaySummary(daySlots, date)

        scheduleContainer.removeAllViews()
        emptyCard.visibility = if (daySlots.isEmpty()) View.VISIBLE else View.GONE
        if (daySlots.isEmpty()) {
            emptyText.text = if (AcademicWeek.isUniversityHoliday(date)) {
                "Zi liberă în calendarul universitar."
            } else {
                "Nicio oră pentru ${group.name} în ziua asta, în săptămâna ${if (parity == WeekParity.ODD) "impară" else "pară"}."
            }
        } else {
            daySlots.forEach { scheduleContainer.addView(slotCard(it, data.catalog)) }
        }
    }

    private fun buildDaySummary(slots: List<ScheduleSlot>, date: LocalDate): String {
        if (AcademicWeek.isUniversityHoliday(date)) return "Zi liberă în calendarul universitar"
        if (slots.isEmpty()) return "Zi liberă în orarul selectat"
        val activityCount = slots.sumOf { it.activities.size }
        if (date == LocalDate.now()) {
            val now = LocalTime.now()
            val active = slots.firstOrNull {
                val start = LocalTime.of(it.startHour.coerceAtMost(23), 0)
                val end = if (it.endHour >= 24) LocalTime.MAX else LocalTime.of(it.endHour, 0)
                !now.isBefore(start) && now.isBefore(end)
            }
            if (active != null) return "Acum · ${active.timeLabel}"
            val next = slots.firstOrNull { LocalTime.of(it.startHour.coerceAtMost(23), 0).isAfter(now) }
            if (next != null) return "Următoarea la %02d:00 · %d activități azi".format(next.startHour, activityCount)
            return "Ai terminat pe azi · $activityCount activități"
        }
        return "Prima oră la %02d:00 · %d activități".format(slots.first().startHour, activityCount)
    }

    private fun slotCard(slot: ScheduleSlot, catalog: SubjectCatalog): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = 0
            setCardBackgroundColor(color(R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(dp(14), dp(14), dp(16), dp(14))
        }

        val timeColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(context, R.drawable.time_pill)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        timeColumn.addView(TextView(this).apply {
            text = "%02d:00".format(slot.startHour)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text_primary))
        })
        timeColumn.addView(TextView(this).apply {
            text = "%02d:00".format(slot.endHour)
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(1), 0, 0)
        })
        if (slot.parity != WeekParity.BOTH) {
            timeColumn.addView(badge(
                if (slot.parity == WeekParity.ODD) "IMPAR" else "PAR",
                R.color.accent,
                R.color.accent_soft,
            ).apply {
                textSize = 9f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(7) }
            })
        }
        row.addView(timeColumn)

        row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(1))
        })

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        slot.activities.forEachIndexed { index, activity ->
            if (index > 0) {
                content.addView(View(this).apply {
                    setBackgroundColor(color(R.color.outline))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(12)
                    }
                })
            }
            content.addView(activityBlock(activity, catalog))
        }
        row.addView(content)
        card.addView(row)
        return card
    }

    private fun activityBlock(activity: ScheduleActivity, catalog: SubjectCatalog): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val resolved = catalog.find(activity.code)
            val fullName = resolved?.fullName?.takeIf { it.isNotBlank() } ?: activity.subject
            val code = resolved?.code ?: activity.code
            addView(TextView(context).apply {
                text = buildString {
                    append(fullName)
                    if (!code.isNullOrBlank() && !fullName.equals(code, ignoreCase = true)) append(" ($code)")
                }
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text_primary))
                setLineSpacing(0f, 1.08f)
            })

            val badges = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            val typeColors = activity.type?.let { type ->
                when (type) {
                    ActivityType.COURSE -> R.color.course to R.color.course_soft
                    ActivityType.LAB -> R.color.lab to R.color.lab_soft
                    ActivityType.SEMINAR -> R.color.seminar to R.color.seminar_soft
                }
            }
            when {
                activity.optional && activity.type != null && typeColors != null -> {
                    val label = "${activity.type.displayName.replaceFirstChar { it.titlecase() }} opțional"
                    badges.addView(badge(label, typeColors.first, typeColors.second))
                }
                activity.type != null && typeColors != null -> {
                    badges.addView(badge(activity.type.displayName.replaceFirstChar { it.titlecase() }, typeColors.first, typeColors.second))
                }
                activity.optional -> {
                    badges.addView(badge("Opțional", R.color.optional, R.color.optional_soft))
                }
            }
            if (activity.subgroupIndex > 0) {
                badges.addView(badge("Subgrupa ${activity.subgroupIndex}", R.color.text_secondary, R.color.surface_variant).apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(6)
                })
            }
            if (badges.childCount > 0) addView(badges)

            if (!activity.room.isNullOrBlank()) {
                addView(TextView(context).apply {
                    text = activity.room
                    textSize = 13f
                    setTextColor(color(R.color.text_secondary))
                    setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_place_20, 0, 0, 0)
                    compoundDrawablePadding = dp(5)
                    setPadding(0, dp(9), 0, 0)
                })
            }
        }
    }

    private fun badge(textValue: String, foregroundRes: Int, backgroundRes: Int): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(foregroundRes))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(99).toFloat()
                setColor(color(backgroundRes))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun buildSourceFooter(data: LoadedSchedule, optionals: Set<String>): String {
        val selectedNames = optionals.mapNotNull { data.catalog.subjects[it]?.code }.sorted()
        return buildString {
            append(data.sourceLabel)
            if (selectedNames.isNotEmpty()) append("\nOpționale: ${selectedNames.joinToString(", ")}")
        }
    }

    private fun loadSeriesAsync(series: String, generation: Int, onLoaded: (LoadedSchedule) -> Unit) {
        executor.execute {
            val result = runCatching { readSeries(series) }
            runOnUiThread {
                if (generation != loadGeneration.get() || isFinishing) return@runOnUiThread
                result.onSuccess(onLoaded).onFailure { error ->
                    setupProgress.visibility = View.GONE
                    mainProgress.visibility = View.GONE
                    saveConfigurationButton.isEnabled = true
                    Toast.makeText(this, "Nu am putut citi orarul: ${error.message ?: error.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun readSeries(series: String): LoadedSchedule {
        require(series in seriesOrder) { "Serie necunoscută: $series" }
        val sheet = BundledScheduleReader.read(assets, series)
        return buildLoadedSchedule(series, sheet, "Sursă: orarul ACS inclus · seria $series")
    }

    private fun buildLoadedSchedule(series: String, sheet: GridSheet, source: String): LoadedSchedule {
        val groups = ScheduleParser.detectGroups(sheet)
        require(groups.isNotEmpty()) { "Nu am detectat grupele în fișier." }
        val detectedSeries = groups.map { it.name.removePrefix(it.number).trim().uppercase() }.distinct()
        require(detectedSeries.size == 1 && detectedSeries.single() == series) {
            "Fișierul nu pare să fie pentru seria $series."
        }
        val catalog = SubjectCatalogParser.parse(sheet)
        return LoadedSchedule(series, sheet, groups, catalog, source)
    }

    private fun dayName(dayOfWeek: DayOfWeek): String? = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "LUNI"
        DayOfWeek.TUESDAY -> "MARȚI"
        DayOfWeek.WEDNESDAY -> "MIERCURI"
        DayOfWeek.THURSDAY -> "JOI"
        DayOfWeek.FRIDAY -> "VINERI"
        else -> null
    }

    private fun groupKey(series: String) = "group_$series"
    private fun optionalKey(series: String) = "optionals_$series"
    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class LoadedSchedule(
        val series: String,
        val sheet: GridSheet,
        val groups: List<GroupInfo>,
        val catalog: SubjectCatalog,
        val sourceLabel: String,
    )

    companion object {
        private const val KEY_CURRENT_SERIES = "current_series"
        private const val WEEK_NAVIGATION_RADIUS = 2
    }
}
