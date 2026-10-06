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
import android.widget.ScrollView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
import ro.upb.orarreader.parser.FacultativeScheduleParser
import ro.upb.orarreader.parser.OptionalScheduleParser
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
    private lateinit var subgroupCard: MaterialCardView
    private lateinit var subgroupDropdown: AutoCompleteTextView
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
    private lateinit var addCustomActivityButton: MaterialButton
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
    private val manualSeminarEditors = linkedMapOf<String, ManualSeminarEditor>()
    private val optionalAllocationEditors = linkedMapOf<String, OptionalAllocationEditor>()
    private var psychologyCheck: MaterialCheckBox? = null
    private var frenchCheck: MaterialCheckBox? = null
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
        UpdateChecker.checkAutomatically(this)

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
        subgroupCard = findViewById(R.id.subgroupCard)
        subgroupDropdown = findViewById(R.id.subgroupDropdown)
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
        addCustomActivityButton = findViewById(R.id.addCustomActivityButton)
        addCustomActivityButton.setOnClickListener { showCustomActivityDialog(null) }
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
                chipCornerRadius = dp(8).toFloat()
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
        subgroupCard.visibility = View.GONE
        subgroupDropdown.isEnabled = false
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
            groupDropdown.setOnItemClickListener { _, _, position, _ ->
                val groupNumber = groupAdapter.getItem(position) ?: return@setOnItemClickListener
                configureGlobalSubgroup(data, groupNumber)
            }

            val savedGroup = preferences.getString(groupKey(series), null)
                ?.takeIf { it in groupNumbers }
                ?: groupNumbers.firstOrNull().orEmpty()
            groupDropdown.setText(savedGroup, false)
            configureGlobalSubgroup(data, savedGroup)

            val savedOptionals = preferences.getStringSet(optionalKey(series), emptySet())
                ?.map(::canonicalizeSubjectCode)
                ?.toSet()
                .orEmpty()
            populateOptionalChecks(data, savedOptionals)
            sourceText.text = data.sourceLabel
        }
    }

    private fun configureGlobalSubgroup(data: LoadedSchedule, groupNumber: String) {
        val group = data.groups.firstOrNull { it.number == groupNumber }
        if (group == null || group.subgroupCount <= 1) {
            subgroupCard.visibility = View.GONE
            subgroupDropdown.isEnabled = false
            subgroupDropdown.setText(SUBGROUP_ALL_LABEL, false)
            return
        }

        val choices = buildList {
            add(SUBGROUP_ALL_LABEL)
            for (index in 1..group.subgroupCount) add("Subgrupa $index")
        }
        subgroupDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, choices)
        )
        subgroupDropdown.setOnClickListener { subgroupDropdown.showDropDown() }
        subgroupDropdown.isEnabled = true
        subgroupCard.visibility = View.VISIBLE

        val saved = preferences.getInt(subgroupKey(data.series, group.number), 0)
            .coerceIn(0, group.subgroupCount)
        subgroupDropdown.setText(
            if (saved == 0) SUBGROUP_ALL_LABEL else "Subgrupa $saved",
            false,
        )
    }

    private fun selectedGlobalSubgroup(group: GroupInfo): Int {
        if (group.subgroupCount <= 1) return 0
        return Regex("""^Subgrupa\s+(\d+)$""", RegexOption.IGNORE_CASE)
            .matchEntire(subgroupDropdown.text.toString().trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(1, group.subgroupCount)
            ?: 0
    }


    private fun populateOptionalChecks(data: LoadedSchedule, selected: Set<String>) {
        optionalList.removeAllViews()
        manualSeminarEditors.clear()
        optionalAllocationEditors.clear()
        psychologyCheck = null
        frenchCheck = null

        val catalog = data.catalog
        val manualActivityTypes = OptionalScheduleParser.manualActivityTypes(data.sheet, catalog)
        val facultativeInfo = FacultativeScheduleParser.parse(data.sheet)
        val optionals = catalog.optionalSubjects.sortedWith(compareBy({ optionalCategoryRank(it.code) }, { it.code.lowercase() }))
        val hasFacultatives = facultativeInfo.psychologyCourses.isNotEmpty() || facultativeInfo.frenchSeminarManual
        optionalCard.visibility = if (optionals.isEmpty() && !hasFacultatives) View.GONE else View.VISIBLE

        val allocationCandidates = OptionalAllocations.candidates(data.sheet, catalog)

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

            val dependentViews = mutableListOf<View>()

            val manualType = manualActivityTypes[subject.canonicalCode]
            if (manualType != null) {
                val editor = createManualActivityEditor(
                    data.series,
                    subject.canonicalCode,
                    manualType,
                    "stabilit la curs",
                )
                editor.root.visibility = if (check.isChecked) View.VISIBLE else View.GONE
                manualSeminarEditors[subject.canonicalCode] = editor
                optionalList.addView(editor.root)
                dependentViews += editor.root
            }

            val byType = allocationCandidates[subject.canonicalCode].orEmpty()
            for ((activityType, candidates) in byType) {
                if (candidates.size <= 1) continue

                val root = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(12))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(8).toFloat()
                        setColor(color(R.color.surface_variant))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(10) }
                    visibility = if (check.isChecked) View.VISIBLE else View.GONE
                }

                val typeName = when (activityType) {
                    ActivityType.LAB -> "laboratorul"
                    ActivityType.SEMINAR -> "seminarul"
                    ActivityType.COURSE -> "cursul"
                }
                root.addView(TextView(this).apply {
                    text = "Alege $typeName tău"
                    textSize = 13.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(color(R.color.text_primary))
                })
                root.addView(TextView(this).apply {
                    text = "Orarul oficial listează mai multe variante. Alege doar intervalul la care ai fost repartizat."
                    textSize = 12f
                    setTextColor(color(R.color.text_secondary))
                    setPadding(0, dp(3), 0, dp(2))
                })

                val savedId = OptionalAllocations.readSelection(
                    preferences,
                    data.series,
                    subject.canonicalCode,
                    activityType,
                )
                val selectedCandidate = candidates.firstOrNull { it.id == savedId }
                val options = listOf(ALLOCATION_NONE_LABEL) + candidates.map { it.displayLabel }
                val input = addManualDropdown(
                    root,
                    if (activityType == ActivityType.LAB) "Laborator" else "Seminar",
                    options,
                    selectedCandidate?.displayLabel ?: ALLOCATION_NONE_LABEL,
                )
                val key = "${subject.canonicalCode}|${activityType.name}"
                optionalAllocationEditors[key] = OptionalAllocationEditor(
                    code = subject.canonicalCode,
                    type = activityType,
                    input = input,
                    candidates = candidates,
                )
                optionalList.addView(root)
                dependentViews += root
            }

            if (dependentViews.isNotEmpty()) {
                check.setOnCheckedChangeListener { _, checked ->
                    dependentViews.forEach { it.visibility = if (checked) View.VISIBLE else View.GONE }
                }
            }
        }

        if (hasFacultatives) {
            optionalList.addView(TextView(this).apply {
                text = "Facultative"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(color(R.color.text_primary))
                setPadding(0, dp(14), 0, dp(4))
            })
        }

        if (facultativeInfo.psychologyCourses.isNotEmpty()) {
            val check = MaterialCheckBox(this).apply {
                text = "Psihologia educației"
                textSize = 14f
                setTextColor(color(R.color.text_primary))
                isChecked = FacultativeSchedules.isPsychologyEnabled(preferences, data.series)
                buttonTintList = ColorStateList.valueOf(color(R.color.accent))
                minHeight = dp(48)
            }
            psychologyCheck = check
            optionalList.addView(check)

            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (check.isChecked) View.VISIBLE else View.GONE
                setPadding(dp(12), dp(8), dp(12), dp(10))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(color(R.color.surface_variant))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(10) }
            }

            panel.addView(TextView(this).apply {
                text = "Cursuri: luni 12:00–14:00 · CantiCTI și joi 10:00–12:00 · A04 Leu"
                textSize = 12.5f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(6), 0, dp(4))
            })

            if (facultativeInfo.psychologySeminarManual) {
                val editor = createManualActivityEditor(
                    data.series,
                    FacultativeSchedules.PSYCHOLOGY_KEY,
                    ActivityType.SEMINAR,
                    "stabilit la curs",
                )
                manualSeminarEditors[FacultativeSchedules.PSYCHOLOGY_KEY] = editor
                panel.addView(editor.root)
            }

            check.setOnCheckedChangeListener { _, checked ->
                panel.visibility = if (checked) View.VISIBLE else View.GONE
            }
            optionalList.addView(panel)
        }

        if (facultativeInfo.frenchSeminarManual) {
            val check = MaterialCheckBox(this).apply {
                text = "Franceză · seminar facultativ"
                textSize = 14f
                setTextColor(color(R.color.text_primary))
                isChecked = FacultativeSchedules.isFrenchEnabled(preferences, data.series)
                buttonTintList = ColorStateList.valueOf(color(R.color.accent))
                minHeight = dp(48)
            }
            frenchCheck = check
            optionalList.addView(check)

            val editor = createManualActivityEditor(
                data.series,
                FacultativeSchedules.FRENCH_KEY,
                ActivityType.SEMINAR,
                "stabilit cu profesorul",
            )
            manualSeminarEditors[FacultativeSchedules.FRENCH_KEY] = editor
            editor.root.visibility = if (check.isChecked) View.VISIBLE else View.GONE
            check.setOnCheckedChangeListener { _, checked ->
                editor.root.visibility = if (checked) View.VISIBLE else View.GONE
            }
            optionalList.addView(editor.root)
        }
    }

    private fun createManualActivityEditor(
        series: String,
        code: String,
        activityType: ActivityType,
        assignmentLabel: String,
    ): ManualSeminarEditor {
        val config = CustomOptionalSeminars.read(preferences, series, code)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(color(R.color.surface_variant))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val activityName = when (activityType) {
            ActivityType.LAB -> "Laborator"
            ActivityType.SEMINAR -> "Seminar"
            ActivityType.COURSE -> "Curs"
        }
        val activityNameLower = activityName.lowercase()

        root.addView(TextView(this).apply {
            text = "$activityName $assignmentLabel"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text_primary))
        })
        root.addView(TextView(this).apply {
            text = "Când afli repartizarea, o poți adăuga aici în orar și în notificări."
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(3), 0, dp(4))
        })

        val enabled = MaterialCheckBox(this).apply {
            text = "Am aflat programul $activityNameLower"
            isChecked = config.enabled
            buttonTintList = ColorStateList.valueOf(color(R.color.accent))
        }
        root.addView(enabled)

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (config.enabled) View.VISIBLE else View.GONE
        }
        root.addView(details)

        val day = addManualDropdown(details, "Ziua", days, config.day.takeIf { it in days } ?: "LUNI")
        val intervals = (8..18).map { start -> "%02d:00 – %02d:00".format(start, start + 2) }
        val selectedInterval = "%02d:00 – %02d:00".format(config.startHour, config.endHour)
            .takeIf { it in intervals } ?: "08:00 – 10:00"
        val interval = addManualDropdown(details, "Interval", intervals, selectedInterval)
        val parityOptions = listOf("În fiecare săptămână", "Impar", "Par")
        val selectedParity = when (config.parity) {
            WeekParity.ODD -> "Impar"
            WeekParity.EVEN -> "Par"
            WeekParity.BOTH -> "În fiecare săptămână"
        }
        val parity = addManualDropdown(details, "Săptămâna", parityOptions, selectedParity)

        val roomLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = "Sala (opțional)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val room = TextInputEditText(this).apply {
            setText(config.room)
            minHeight = dp(56)
            setSingleLine(true)
        }
        roomLayout.addView(room)
        details.addView(roomLayout)

        enabled.setOnCheckedChangeListener { _, checked ->
            details.visibility = if (checked) View.VISIBLE else View.GONE
        }

        return ManualSeminarEditor(root, enabled, day, interval, parity, room)
    }

    private fun addManualDropdown(
        parent: LinearLayout,
        hint: String,
        options: List<String>,
        selected: String,
    ): AutoCompleteTextView {
        val layout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val input = AutoCompleteTextView(this).apply {
            inputType = 0
            minHeight = dp(56)
            setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, options))
            setText(selected, false)
            setOnClickListener { showDropDown() }
        }
        layout.addView(input)
        parent.addView(layout)
        return input
    }

    private fun saveManualSeminars(series: String) {
        for ((code, editor) in manualSeminarEditors) {
            val match = Regex("""(\d{2}):00\s*[–-]\s*(\d{2}):00""")
                .find(editor.interval.text.toString())
            val start = match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 8
            val end = match?.groupValues?.getOrNull(2)?.toIntOrNull() ?: (start + 2)
            val parity = when (editor.parity.text.toString()) {
                "Impar" -> WeekParity.ODD
                "Par" -> WeekParity.EVEN
                else -> WeekParity.BOTH
            }
            CustomOptionalSeminars.write(
                preferences,
                series,
                code,
                CustomOptionalSeminars.Config(
                    enabled = editor.enabled.isChecked,
                    day = editor.day.text.toString().takeIf { it in days } ?: "LUNI",
                    startHour = start,
                    endHour = end,
                    parity = parity,
                    room = editor.room.text?.toString()?.trim().orEmpty(),
                ),
            )
        }
    }

    private fun saveOptionalAllocations(series: String) {
        for ((_, editor) in optionalAllocationEditors) {
            val selectedText = editor.input.text.toString()
            val selectedId = editor.candidates.firstOrNull { it.displayLabel == selectedText }?.id
            OptionalAllocations.writeSelection(
                preferences,
                series,
                editor.code,
                editor.type,
                selectedId,
            )
        }
    }

    private fun saveFacultatives(series: String) {
        FacultativeSchedules.setEnabled(
            preferences,
            series,
            psychology = psychologyCheck?.isChecked == true,
            french = frenchCheck?.isChecked == true,
        )


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
        saveManualSeminars(selectedSeries)
        saveOptionalAllocations(selectedSeries)
        saveFacultatives(selectedSeries)
        val subgroup = selectedGlobalSubgroup(group)
        preferences.edit()
            .putString(KEY_CURRENT_SERIES, selectedSeries)
            .putString(groupKey(selectedSeries), groupNumber)
            .putInt(subgroupKey(selectedSeries, groupNumber), subgroup)
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
            val subgroup = if (group.subgroupCount <= 1) {
                0
            } else {
                preferences.getInt(subgroupKey(series, group.number), 0)
                    .coerceIn(0, group.subgroupCount)
            }
            executor.execute {
                val parsedSlots = ScheduleParser.parseForGroup(data.sheet, group, optionals, data.catalog)
                val selectedOptionalAllocations = OptionalAllocations.selectedSlots(
                    preferences,
                    series,
                    optionals,
                    data.sheet,
                    data.catalog,
                )
                val userCustomActivities = UserCustomActivities.slots(
                    preferences,
                    series,
                    group.number,
                )
                val manualActivityTypes = OptionalScheduleParser.manualActivityTypes(data.sheet, data.catalog)
                val customOptionalActivities = CustomOptionalSeminars.slots(
                    preferences,
                    series,
                    optionals,
                    manualActivityTypes,
                    data.catalog,
                )
                val facultativeInfo = FacultativeScheduleParser.parse(data.sheet)
                val facultativeActivities =
                    FacultativeSchedules.psychologyCourseSlots(preferences, series, facultativeInfo) +
                        FacultativeSchedules.manualSlots(preferences, series, facultativeInfo)
                val mergedSlots = CustomOptionalSeminars.mergeSlots(
                    CustomOptionalSeminars.mergeSlots(
                        CustomOptionalSeminars.mergeSlots(
                            CustomOptionalSeminars.mergeSlots(parsedSlots, customOptionalActivities),
                            selectedOptionalAllocations,
                        ),
                        facultativeActivities,
                    ),
                    userCustomActivities,
                )
                val slots = filterSlotsForSubgroup(mergedSlots, subgroup)
                runOnUiThread {
                    if (generation != loadGeneration.get() || isFinishing) return@runOnUiThread
                    currentData = data
                    currentGroup = group
                    currentOptionals = optionals
                    currentSlots = slots
                    mainProgress.visibility = View.GONE
                    scheduleScroll.visibility = View.VISIBLE
                    toolbar.title = "ACS Orar"
                    toolbar.subtitle = if (subgroup > 0) "${group.name} · Subgrupa $subgroup" else group.name
                    profileBadge.text = if (subgroup > 0) "${group.name} · SG$subgroup" else group.name
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
                    dataSourceFooter.text = buildSourceFooter(data, optionals, subgroup)
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
            radius = dp(8).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(R.color.outline)
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
                activity.facultative && activity.type != null && typeColors != null -> {
                    val label = "${activity.type.displayName.replaceFirstChar { it.titlecase() }} facultativ"
                    badges.addView(badge(label, typeColors.first, typeColors.second))
                }
                activity.facultative -> {
                    badges.addView(badge("Facultativ", R.color.optional, R.color.optional_soft))
                }
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
            if (activity.customId != null) {
                badges.addView(badge("Personalizat", R.color.optional, R.color.optional_soft).apply {
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

            if (activity.customId != null) {
                addView(TextView(context).apply {
                    text = "Apasă pentru editare"
                    textSize = 11f
                    setTextColor(color(R.color.accent))
                    setPadding(0, dp(8), 0, 0)
                })
                isClickable = true
                isFocusable = true
                setOnClickListener { showCustomActivityDialog(activity.customId) }
            }
        }
    }

    private fun showCustomActivityDialog(customId: String?) {
        val data = currentData ?: return
        val group = currentGroup ?: return
        val existing = customId?.let { id ->
            UserCustomActivities.read(preferences, data.series, group.number)
                .firstOrNull { it.id == id }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val subjectLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = "Denumire"
        }
        val subjectInput = TextInputEditText(this).apply {
            setText(existing?.subject.orEmpty())
            minHeight = dp(56)
            setSingleLine(true)
        }
        subjectLayout.addView(subjectInput)
        content.addView(subjectLayout)

        val typeLabels = listOf("Fără tip", "Curs", "Laborator", "Seminar")
        val initialType = when (existing?.type) {
            ActivityType.COURSE -> "Curs"
            ActivityType.LAB -> "Laborator"
            ActivityType.SEMINAR -> "Seminar"
            null -> "Fără tip"
        }
        val typeInput = addManualDropdown(content, "Tip", typeLabels, initialType)
        val dayInput = addManualDropdown(
            content,
            "Ziua",
            days,
            existing?.day?.takeIf { it in days } ?: selectedDay,
        )

        val intervalOptions = buildList {
            for (startHour in 8..20) {
                for (duration in 1..4) {
                    val endHour = startHour + duration
                    if (endHour <= 22) add("%02d:00 – %02d:00".format(startHour, endHour))
                }
            }
        }
        val existingInterval = existing?.let {
            "%02d:00 – %02d:00".format(it.startHour, it.endHour)
        }
        val intervalInput = addManualDropdown(
            content,
            "Interval",
            intervalOptions,
            existingInterval?.takeIf { it in intervalOptions } ?: "08:00 – 10:00",
        )

        val parityOptions = listOf("În fiecare săptămână", "Impar", "Par")
        val parityInput = addManualDropdown(
            content,
            "Săptămâna",
            parityOptions,
            when (existing?.parity ?: WeekParity.BOTH) {
                WeekParity.ODD -> "Impar"
                WeekParity.EVEN -> "Par"
                WeekParity.BOTH -> "În fiecare săptămână"
            },
        )

        val roomLayout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = "Sala (opțional)"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val roomInput = TextInputEditText(this).apply {
            setText(existing?.room.orEmpty())
            minHeight = dp(56)
            setSingleLine(true)
        }
        roomLayout.addView(roomInput)
        content.addView(roomLayout)

        val scroll = ScrollView(this).apply { addView(content) }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) "Adaugă activitate" else "Editează activitatea")
            .setView(scroll)
            .setNeutralButton("Anulează", null)
            .setPositiveButton("Salvează") { _, _ ->
                val subject = subjectInput.text?.toString()?.trim().orEmpty()
                if (subject.isBlank()) {
                    Toast.makeText(this, "Scrie denumirea activității.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val intervalMatch = Regex("""(\d{2}):00\s*[–-]\s*(\d{2}):00""")
                    .find(intervalInput.text.toString())
                val startHour = intervalMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 8
                val endHour = intervalMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 10
                val type = when (typeInput.text.toString()) {
                    "Curs" -> ActivityType.COURSE
                    "Laborator" -> ActivityType.LAB
                    "Seminar" -> ActivityType.SEMINAR
                    else -> null
                }
                val parity = when (parityInput.text.toString()) {
                    "Impar" -> WeekParity.ODD
                    "Par" -> WeekParity.EVEN
                    else -> WeekParity.BOTH
                }

                UserCustomActivities.upsert(
                    preferences,
                    data.series,
                    group.number,
                    UserCustomActivities.Entry(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        subject = subject,
                        type = type,
                        day = dayInput.text.toString().takeIf { it in days } ?: "LUNI",
                        startHour = startHour,
                        endHour = endHour,
                        parity = parity,
                        room = roomInput.text?.toString()?.trim().orEmpty(),
                    ),
                )
                showSavedSchedule(data.series)
            }

        if (existing != null) {
            builder.setNegativeButton("Șterge") { _, _ ->
                UserCustomActivities.delete(preferences, data.series, group.number, existing.id)
                showSavedSchedule(data.series)
            }
        }

        builder.show()
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
                cornerRadius = dp(6).toFloat()
                setColor(color(backgroundRes))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun filterSlotsForSubgroup(slots: List<ScheduleSlot>, subgroup: Int): List<ScheduleSlot> {
        if (subgroup <= 0) return slots
        return slots.mapNotNull { slot ->
            val activities = slot.activities.filter {
                it.subgroupIndex == 0 || it.subgroupIndex == subgroup
            }
            if (activities.isEmpty()) null else slot.copy(activities = activities)
        }
    }


    private fun buildSourceFooter(data: LoadedSchedule, optionals: Set<String>, subgroup: Int): String {
        val selectedNames = optionals.mapNotNull { data.catalog.subjects[it]?.code }.sorted()
        val facultativeInfo = FacultativeScheduleParser.parse(data.sheet)
        val facultatives = buildList {
            if (FacultativeSchedules.isPsychologyEnabled(preferences, data.series)) add("Psihologia educației")
            if (FacultativeSchedules.isFrenchEnabled(preferences, data.series)) add("Franceză")
        }
        return buildString {
            append(data.sourceLabel)
            if (subgroup > 0) append("\nSubgrupa: $subgroup")
            if (selectedNames.isNotEmpty()) append("\nOpționale: ${selectedNames.joinToString(", ")}")
            if (facultatives.isNotEmpty()) append("\nFacultative: ${facultatives.joinToString(", ")}")
            if (facultativeInfo.physicalEducationExternalSchedule) {
                append("\nEducație fizică: orarul se confirmă la sala de sport.")
            }
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
    private fun subgroupKey(series: String, group: String) = "subgroup_${series}_$group"
    private fun optionalKey(series: String) = "optionals_$series"
    private fun color(res: Int): Int = ContextCompat.getColor(this, res)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class ManualSeminarEditor(
        val root: LinearLayout,
        val enabled: MaterialCheckBox,
        val day: AutoCompleteTextView,
        val interval: AutoCompleteTextView,
        val parity: AutoCompleteTextView,
        val room: TextInputEditText,
    )

    private data class OptionalAllocationEditor(
        val code: String,
        val type: ActivityType,
        val input: AutoCompleteTextView,
        val candidates: List<OptionalAllocations.Candidate>,
    )

    private data class LoadedSchedule(
        val series: String,
        val sheet: GridSheet,
        val groups: List<GroupInfo>,
        val catalog: SubjectCatalog,
        val sourceLabel: String,
    )

    companion object {
        private const val KEY_CURRENT_SERIES = "current_series"
        private const val SUBGROUP_ALL_LABEL = "Nu știu / Arată ambele"
        private const val ALLOCATION_NONE_LABEL = "Nu știu / Nu afișa"
        private const val WEEK_NAVIGATION_RADIUS = 2
    }
}
