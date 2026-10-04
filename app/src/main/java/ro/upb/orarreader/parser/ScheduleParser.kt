package ro.upb.orarreader.parser

import ro.upb.orarreader.model.ActivityType
import ro.upb.orarreader.model.GridCell
import ro.upb.orarreader.model.GridSheet
import ro.upb.orarreader.model.GridStyleCell
import ro.upb.orarreader.model.GroupInfo
import ro.upb.orarreader.model.ScheduleActivity
import ro.upb.orarreader.model.ScheduleSlot
import ro.upb.orarreader.model.SubjectCatalog
import ro.upb.orarreader.model.WeekParity
import ro.upb.orarreader.model.canonicalizeSubjectCode
import java.text.Normalizer

object ScheduleParser {
    private val groupRegex = Regex("^\\d{3}\\s*[A-ZĂÂÎȘȚ]{1,4}$", RegexOption.IGNORE_CASE)
    private val timeRegex = Regex("^(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})$")
    private val fallbackDays = listOf("LUNI", "MARȚI", "MIERCURI", "JOI", "VINERI", "SÂMBĂTĂ")
    private val normalizedDays = fallbackDays.map(::normalize).toSet()

    fun detectGroups(sheet: GridSheet): List<GroupInfo> {
        return sheet.cells
            .filter { groupRegex.matches(it.text.trim()) }
            .sortedWith(compareBy<GridCell> { it.rowStart }.thenBy { it.colStart })
            .map {
                GroupInfo(
                    name = it.text.trim().replace(Regex("\\s+"), " "),
                    startCol = it.colStart,
                    endCol = it.colEnd,
                )
            }
            .distinctBy { it.name }
    }

    fun findGroupByNumber(sheet: GridSheet, groupNumber: String): GroupInfo? {
        val wanted = groupNumber.trim()
        return detectGroups(sheet).firstOrNull { it.number == wanted }
    }

    /**
     * Returns elective codes that are actually referenced by this group's timetable.
     * If the grid only says "conform legendei", the workbook-wide elective catalog is used as fallback.
     */
    fun detectOptionalCodesForGroup(
        sheet: GridSheet,
        group: GroupInfo,
        catalog: SubjectCatalog = SubjectCatalogParser.parse(sheet),
    ): Set<String> {
        val bands = detectTimeBands(sheet)
        if (bands.isEmpty()) return emptySet()

        val result = linkedSetOf<String>()
        var sawGenericOptional = false
        val cells = sheet.cells.filter { cell ->
            cell.colEnd >= group.startCol && cell.colStart <= group.endCol &&
                bands.any { rangesIntersect(cell.rowStart, cell.rowEnd, it.rowStart, it.rowEnd) }
        }

        for (cell in cells) {
            val text = cell.text
            val code = SubjectCatalogParser.extractCode(text)
            if (catalog.isOptional(code)) result += canonicalizeSubjectCode(code.orEmpty())

            if (SubjectCatalogParser.isOptionalText(text)) {
                sawGenericOptional = true
                SubjectCatalogParser.codesMentioned(text, catalog)
                    .filterTo(result) { it in catalog.optionalCodes || it.isNotBlank() }
            }
        }

        if (result.isEmpty() && sawGenericOptional) result += catalog.optionalCodes
        return result
    }


    /** Elective codes that are actually referenced inside the timetable grid (not just in the legend). */
    fun detectOptionalCodesInTimetable(
        sheet: GridSheet,
        catalog: SubjectCatalog = SubjectCatalogParser.parse(sheet),
    ): Set<String> {
        val bands = detectTimeBands(sheet)
        val groups = detectGroups(sheet)
        if (bands.isEmpty() || groups.isEmpty()) return emptySet()

        val minCol = groups.minOf { it.startCol }
        val maxCol = groups.maxOf { it.endCol }
        val result = linkedSetOf<String>()

        for (cell in sheet.cells) {
            if (cell.colEnd < minCol || cell.colStart > maxCol) continue
            if (bands.none { rangesIntersect(cell.rowStart, cell.rowEnd, it.rowStart, it.rowEnd) }) continue

            val code = SubjectCatalogParser.extractCode(cell.text)
            if (catalog.isOptional(code)) result += canonicalizeSubjectCode(code.orEmpty())

            if (SubjectCatalogParser.isOptionalText(cell.text)) {
                SubjectCatalogParser.codesMentioned(cell.text, catalog)
                    .filterTo(result) { it in catalog.optionalCodes }
            }
        }
        return result
    }

    fun parseForGroup(
        sheet: GridSheet,
        group: GroupInfo,
        selectedOptionalCodes: Set<String> = emptySet(),
        catalog: SubjectCatalog = SubjectCatalogParser.parse(sheet),
    ): List<ScheduleSlot> {
        val bands = detectTimeBands(sheet)
        if (bands.isEmpty()) return emptyList()

        val rawEvents = if (sheet.styleCells.isNotEmpty()) {
            extractStyledRawEvents(sheet, group, bands)
        } else {
            extractLegacyRawEvents(sheet, group, bands)
        }

        val collapsedEvents = rawEvents
            .groupBy {
                EventIdentity(
                    day = it.day,
                    startHour = it.startHour,
                    endHour = it.endHour,
                    parity = it.parity,
                    subjectRaw = cleanWhitespace(it.subjectRaw),
                    roomRaw = cleanWhitespace(it.roomRaw.orEmpty()),
                    rowStart = it.rowStart,
                    rowEnd = it.rowEnd,
                )
            }
            .map { (_, events) ->
                val first = events.first()
                val subgroupIndex = if (events.map { it.subgroupIndex }.distinct().size > 1) 0 else first.subgroupIndex
                ParsedEvent(
                    day = first.day,
                    startHour = first.startHour,
                    endHour = first.endHour,
                    parity = first.parity,
                    activity = ActivityTextParser.parse(first.subjectRaw, first.roomRaw, subgroupIndex, catalog),
                )
            }

        val selectedCanonical = selectedOptionalCodes
            .map(::canonicalizeSubjectCode)
            .filterTo(linkedSetOf()) { it.isNotBlank() }

        val dayOrder = bands.map { it.day }.distinct().withIndex().associate { it.value to it.index }
        var slots = collapsedEvents
            .groupBy { SlotIdentity(it.day, it.startHour, it.endHour, it.parity) }
            .mapNotNull { (identity, events) ->
                val activities = events
                    .map { it.activity }
                    .filter { shouldKeepActivity(it, selectedCanonical, catalog) }
                    .distinct()
                    .sortedWith(compareBy<ScheduleActivity> { if (it.subgroupIndex == 0) -1 else it.subgroupIndex })
                if (activities.isEmpty()) null else ScheduleSlot(
                    day = identity.day,
                    startHour = identity.startHour,
                    endHour = identity.endHour,
                    parity = identity.parity,
                    activities = activities,
                )
            }

        // Some series place elective times only in the legend. Add those when the selected code
        // did not already produce a matching grid activity.
        if (selectedCanonical.isNotEmpty()) {
            val fromGrid = parseSelectedOptionalCoursesFromWholeGrid(sheet, selectedCanonical, catalog, bands)
                .filterNot { candidate ->
                    slots.any { existing ->
                        existing.day == candidate.day &&
                            existing.startHour == candidate.startHour &&
                            existing.endHour == candidate.endHour &&
                            existing.parity == candidate.parity &&
                            candidate.activities.any { added ->
                                existing.activities.any { current ->
                                    added.type == current.type &&
                                        canonicalizeSubjectCode(added.code.orEmpty()) == canonicalizeSubjectCode(current.code.orEmpty())
                                }
                            }
                    }
                }
            val fromLegend = OptionalScheduleParser.parse(sheet, selectedCanonical, catalog)
                .mapNotNull { candidate ->
                    val activities = candidate.activities.filter { activity ->
                        activity.type == ActivityType.COURSE ||
                            !conflictsWithRequiredSchedule(candidate, slots)
                    }
                    if (activities.isEmpty()) null else candidate.copy(activities = activities)
                }
            slots = mergeSlots(slots + fromGrid + fromLegend)
        }

        slots = slots.sortedWith(
            compareBy<ScheduleSlot> { dayOrder[it.day] ?: fallbackDays.indexOf(it.day).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }
                .thenBy { it.startHour }
                .thenBy { it.endHour }
                .thenBy { it.parity.ordinal }
        )

        return mergeAdjacentIdenticalSlots(slots)
    }

    /**
     * Border-aware parser for the official ACS XLS files.
     *
     * The workbook often draws one logical two-hour box using two physical one-hour rows.
     * If there is no horizontal border between those rows, the upper half means odd week and
     * the lower half means even week. A subject merged across the whole box, or a subject on the
     * first row followed by its room on the second row, means BOTH weeks instead.
     *
     * Vertical borders are equally important: a label written only in the first physical Excel
     * column may visually span the entire group/series. Reconstructing the drawn horizontal span
     * lets common courses reach every affected group without hardcoding column positions.
     */
    private fun extractStyledRawEvents(
        sheet: GridSheet,
        group: GroupInfo,
        bands: List<TimeBand>,
    ): List<RawEvent> {
        val groups = detectGroups(sheet)
        if (groups.isEmpty()) return emptyList()
        val minGridCol = groups.minOf { it.startCol }
        val maxGridCol = groups.maxOf { it.endCol }
        val styles = sheet.styleCells.associateBy { it.row to it.col }

        val timetableCells = sheet.cells
            .filter { cell ->
                cell.text.isNotBlank() &&
                    !shouldIgnore(cell.text) &&
                    !isGenericOptionalPlaceholder(cell.text) &&
                    bands.any { rangesIntersect(cell.rowStart, cell.rowEnd, it.rowStart, it.rowEnd) }
            }
            .sortedWith(compareBy<GridCell> { it.rowStart }.thenBy { it.colStart })

        val rooms = timetableCells.filter { ActivityTextParser.isRoomOnly(it.text) }
        val events = mutableListOf<RawEvent>()

        for (subjectCell in timetableCells) {
            if (ActivityTextParser.isRoomOnly(subjectCell.text)) continue

            val borderColumns = visualHorizontalSpan(
                sheet = sheet,
                row = subjectCell.rowStart,
                initialStart = subjectCell.colStart,
                initialEnd = subjectCell.colEnd,
                minGridCol = minGridCol,
                maxGridCol = maxGridCol,
                styles = styles,
            )
            val visualColumns = constrainMalformedLocalAlternateSpan(
                sheet = sheet,
                subjectCell = subjectCell,
                groups = groups,
                minGridCol = minGridCol,
                maxGridCol = maxGridCol,
                bands = bands,
                styles = styles,
                borderColumns = borderColumns,
            ) ?: seriesWideAlternatingCourseSpan(
                sheet = sheet,
                subjectCell = subjectCell,
                minGridCol = minGridCol,
                maxGridCol = maxGridCol,
                bands = bands,
                styles = styles,
            ) ?: borderColumns
            if (visualColumns.end < group.startCol || visualColumns.start > group.endCol) continue

            val affectedStart = maxOf(visualColumns.start, group.startCol)
            val affectedEnd = minOf(visualColumns.end, group.endCol)
            val visualRows = visualVerticalSpan(
                sheet = sheet,
                initialStart = subjectCell.rowStart,
                initialEnd = subjectCell.rowEnd,
                colStart = affectedStart,
                colEnd = affectedEnd,
                bands = bands,
                styles = styles,
            )

            val roomCell = rooms
                .asSequence()
                .filter { it.rowStart == subjectCell.rowEnd + 1 }
                .filter { it.rowStart <= visualRows.end }
                .firstOrNull { candidate ->
                    visualHorizontalSpan(
                        sheet = sheet,
                        row = candidate.rowStart,
                        initialStart = candidate.colStart,
                        initialEnd = candidate.colEnd,
                        minGridCol = minGridCol,
                        maxGridCol = maxGridCol,
                        styles = styles,
                    ) == visualColumns
                }

            val contentEndRow = maxOf(subjectCell.rowEnd, roomCell?.rowEnd ?: subjectCell.rowEnd)
            val firstBand = bands.firstOrNull { visualRows.start in it.rowStart..it.rowEnd } ?: continue
            val lastBand = bands.lastOrNull { visualRows.end in it.rowStart..it.rowEnd } ?: continue

            val parity = inferStyledParity(
                text = subjectCell.text,
                contentStartRow = subjectCell.rowStart,
                contentEndRow = contentEndRow,
                blockStartRow = visualRows.start,
                blockEndRow = visualRows.end,
                blockStartHour = firstBand.startHour,
                blockEndHour = lastBand.endHour,
            )

            val subgroupIndex = when {
                group.subgroupCount <= 1 -> 0
                visualColumns.start <= group.startCol && visualColumns.end >= group.endCol -> 0
                affectedStart == affectedEnd -> affectedStart - group.startCol + 1
                else -> 0
            }

            events += RawEvent(
                day = firstBand.day,
                startHour = resolveEventStartHour(subjectCell, firstBand, lastBand),
                endHour = lastBand.endHour,
                parity = parity,
                subgroupIndex = subgroupIndex,
                subjectRaw = subjectCell.text,
                roomRaw = roomCell?.text,
                rowStart = visualRows.start,
                rowEnd = visualRows.end,
            )
        }

        return events
    }

    private fun extractLegacyRawEvents(
        sheet: GridSheet,
        group: GroupInfo,
        bands: List<TimeBand>,
    ): List<RawEvent> {
        val relevantCells = sheet.cells.filter { cell ->
            cell.text.isNotBlank() &&
                cell.colEnd >= group.startCol &&
                cell.colStart <= group.endCol &&
                !shouldIgnore(cell.text) &&
                !isGenericOptionalPlaceholder(cell.text) &&
                bands.any { rangesIntersect(cell.rowStart, cell.rowEnd, it.rowStart, it.rowEnd) }
        }

        val rawEvents = mutableListOf<RawEvent>()
        val subgroupCount = group.subgroupCount

        for (col in group.startCol..group.endCol) {
            val fragments = relevantCells
                .filter { it.colStart <= col && col <= it.colEnd }
                .distinctBy { "${it.rowStart}:${it.rowEnd}:${it.colStart}:${it.colEnd}:${it.text}" }
                .sortedWith(compareBy<GridCell> { it.rowStart }.thenBy { it.rowEnd }.thenBy { it.colStart })

            var index = 0
            while (index < fragments.size) {
                val subjectCell = fragments[index]
                if (ActivityTextParser.isRoomOnly(subjectCell.text)) {
                    index++
                    continue
                }

                var room: String? = null
                var eventEndRow = subjectCell.rowEnd
                val next = fragments.getOrNull(index + 1)
                if (
                    next != null &&
                    ActivityTextParser.isRoomOnly(next.text) &&
                    next.rowStart <= subjectCell.rowEnd + 1
                ) {
                    room = next.text
                    eventEndRow = maxOf(eventEndRow, next.rowEnd)
                    index++
                }

                val firstBandIndex = bands.indexOfFirst { subjectCell.rowStart in it.rowStart..it.rowEnd }
                val lastBandIndex = bands.indexOfLast { eventEndRow in it.rowStart..it.rowEnd }
                if (firstBandIndex < 0 || lastBandIndex < 0) {
                    index++
                    continue
                }

                val firstBand = bands[firstBandIndex]
                val lastBand = bands[lastBandIndex]
                val parity = inferParity(
                    text = subjectCell.text,
                    eventStartRow = subjectCell.rowStart,
                    eventEndRow = eventEndRow,
                    firstBandIndex = firstBandIndex,
                    lastBandIndex = lastBandIndex,
                    band = firstBand,
                )

                rawEvents += RawEvent(
                    day = firstBand.day,
                    startHour = resolveEventStartHour(subjectCell, firstBand, lastBand),
                    endHour = lastBand.endHour,
                    parity = parity,
                    subgroupIndex = if (subgroupCount == 1) 0 else col - group.startCol + 1,
                    subjectRaw = subjectCell.text,
                    roomRaw = room,
                    rowStart = subjectCell.rowStart,
                    rowEnd = eventEndRow,
                )
                index++
            }
        }
        return rawEvents
    }

    /**
     * A few official sheets omit one side border on the local half of an odd/even 2-hour block.
     * Border-only expansion then makes an activity written inside one group's columns bleed into
     * earlier groups. The semantic pattern is still recoverable without knowing the series/day:
     *
     *  - the selected cell is a non-course written inside exactly one group's raw columns;
     *  - border expansion would escape that group;
     *  - the owning group's visual vertical block is exactly two hours;
     *  - the opposite half contains a course that is visually series-wide.
     *
     * In that case the raw column position is the stronger signal for the local half, so clamp it
     * to the owning group. Properly bordered subgroup cells are untouched, as are course-vs-course
     * alternating blocks (which are handled by seriesWideAlternatingCourseSpan).
     */
    private fun constrainMalformedLocalAlternateSpan(
        sheet: GridSheet,
        subjectCell: GridCell,
        groups: List<GroupInfo>,
        minGridCol: Int,
        maxGridCol: Int,
        bands: List<TimeBand>,
        styles: Map<Pair<Int, Int>, GridStyleCell>,
        borderColumns: IntSpan,
    ): IntSpan? {
        if (looksLikeCourse(subjectCell.text)) return null
        if (subjectCell.rowStart != subjectCell.rowEnd) return null

        val owner = groups.singleOrNull {
            subjectCell.colStart >= it.startCol && subjectCell.colEnd <= it.endCol
        } ?: return null

        if (borderColumns.start >= owner.startCol && borderColumns.end <= owner.endCol) return null

        val ownerRows = visualVerticalSpan(
            sheet = sheet,
            initialStart = subjectCell.rowStart,
            initialEnd = subjectCell.rowEnd,
            colStart = owner.startCol,
            colEnd = owner.endCol,
            bands = bands,
            styles = styles,
        )
        if (ownerRows.start == ownerRows.end) return null

        val firstBand = bands.firstOrNull { ownerRows.start in it.rowStart..it.rowEnd } ?: return null
        val lastBand = bands.lastOrNull { ownerRows.end in it.rowStart..it.rowEnd } ?: return null
        if (firstBand.day != lastBand.day || lastBand.endHour - firstBand.startHour != 2) return null

        val hasSeriesWideCourseInOtherHalf = sheet.cells.any { candidate ->
            candidate.rowStart in ownerRows.start..ownerRows.end &&
                candidate.rowStart != subjectCell.rowStart &&
                candidate.text.isNotBlank() &&
                !ActivityTextParser.isRoomOnly(candidate.text) &&
                looksLikeCourse(candidate.text) &&
                visualHorizontalSpan(
                    sheet = sheet,
                    row = candidate.rowStart,
                    initialStart = candidate.colStart,
                    initialEnd = candidate.colEnd,
                    minGridCol = minGridCol,
                    maxGridCol = maxGridCol,
                    styles = styles,
                ) == IntSpan(minGridCol, maxGridCol)
        }
        if (!hasSeriesWideCourseInOtherHalf) return null

        return IntSpan(owner.startCol, owner.endCol)
    }

    /**
     * Some ACS sheets draw two alternating series-wide courses as one outer 2-hour box, but only
     * one of the two physical rows is actually merged in Excel. CC Tuesday 14-16 is a real example:
     * ALGAED is written in the upper row without a merge, while PCLP in the lower row is merged
     * across the whole series. The outer top/bottom border still makes both rows series-wide.
     *
     * Promote the non-merged sibling to the full timetable width only when the geometry is
     * unambiguous: two consecutive one-hour rows, no horizontal divider anywhere between them,
     * a complete outer top/bottom edge, and at least one course cell in the pair merged across
     * all group columns. This avoids globalising ordinary group/subgroup cells.
     */
    private fun seriesWideAlternatingCourseSpan(
        sheet: GridSheet,
        subjectCell: GridCell,
        minGridCol: Int,
        maxGridCol: Int,
        bands: List<TimeBand>,
        styles: Map<Pair<Int, Int>, GridStyleCell>,
    ): IntSpan? {
        if (!looksLikeCourse(subjectCell.text)) return null

        for (topRow in listOf(subjectCell.rowStart - 1, subjectCell.rowStart)) {
            val bottomRow = topRow + 1
            if (topRow < 0 || subjectCell.rowStart !in topRow..bottomRow) continue

            val topBand = bands.firstOrNull { topRow in it.rowStart..it.rowEnd } ?: continue
            val bottomBand = bands.firstOrNull { bottomRow in it.rowStart..it.rowEnd } ?: continue
            if (topBand.day != bottomBand.day) continue
            if (topBand.startHour + 1 != topBand.endHour) continue
            if (bottomBand.startHour + 1 != bottomBand.endHour) continue
            if (topBand.endHour != bottomBand.startHour) continue

            if (!hasFullHorizontalEdge(topRow, minGridCol, maxGridCol, top = true, styles = styles)) continue
            if (!hasFullHorizontalEdge(bottomRow, minGridCol, maxGridCol, top = false, styles = styles)) continue
            if (hasAnyHorizontalBoundary(sheet, topRow, minGridCol, maxGridCol, styles)) continue

            val pairCourses = sheet.cells.filter { cell ->
                cell.rowStart in topRow..bottomRow &&
                    cell.colEnd >= minGridCol && cell.colStart <= maxGridCol &&
                    looksLikeCourse(cell.text)
            }
            if (pairCourses.none { it.colStart <= minGridCol && it.colEnd >= maxGridCol }) continue

            // A non-full-width row is promoted only when the other physical row actually contains
            // another course. This is the odd/even alternating-course pattern, not an empty half.
            if (subjectCell.colStart > minGridCol || subjectCell.colEnd < maxGridCol) {
                if (pairCourses.none { it.rowStart != subjectCell.rowStart }) continue
            }

            return IntSpan(minGridCol, maxGridCol)
        }
        return null
    }

    private fun looksLikeCourse(text: String): Boolean {
        val normalized = normalize(text)
        return Regex("\\((?:C|CURS(?: OPTIONAL)?)\\)").containsMatchIn(normalized) ||
            Regex("\\bCURS\\b").containsMatchIn(normalized)
    }

    private fun hasFullHorizontalEdge(
        row: Int,
        colStart: Int,
        colEnd: Int,
        top: Boolean,
        styles: Map<Pair<Int, Int>, GridStyleCell>,
    ): Boolean = (colStart..colEnd).all { col ->
        val style = styles[row to col]
        if (top) style?.topBorder == true else style?.bottomBorder == true
    }

    private fun hasAnyHorizontalBoundary(
        sheet: GridSheet,
        upperRow: Int,
        colStart: Int,
        colEnd: Int,
        styles: Map<Pair<Int, Int>, GridStyleCell>,
    ): Boolean = (colStart..colEnd).any { col ->
        if (sheet.mergedRanges.any {
                col in it.colStart..it.colEnd && upperRow >= it.rowStart && upperRow < it.rowEnd
            }
        ) {
            false
        } else {
            val upper = styles[upperRow to col]
            val lower = styles[(upperRow + 1) to col]
            upper?.bottomBorder == true || lower?.topBorder == true
        }
    }

    private fun visualHorizontalSpan(
        sheet: GridSheet,
        row: Int,
        initialStart: Int,
        initialEnd: Int,
        minGridCol: Int,
        maxGridCol: Int,
        styles: Map<Pair<Int, Int>, GridStyleCell>,
    ): IntSpan {
        var start = initialStart.coerceAtLeast(minGridCol)
        var end = initialEnd.coerceAtMost(maxGridCol)

        while (start > minGridCol && !hasVerticalBoundary(sheet, row, start - 1, styles)) start--
        while (end < maxGridCol && !hasVerticalBoundary(sheet, row, end, styles)) end++
        return IntSpan(start, end)
    }

    /** Boundary between [leftCol] and leftCol + 1 on the same physical row. */
    private fun hasVerticalBoundary(
        sheet: GridSheet,
        row: Int,
        leftCol: Int,
        styles: Map<Pair<Int, Int>, GridStyleCell>,
    ): Boolean {
        if (sheet.mergedRanges.any {
                row in it.rowStart..it.rowEnd && leftCol >= it.colStart && leftCol < it.colEnd
            }
        ) return false

        val left = styles[row to leftCol]
        val right = styles[row to (leftCol + 1)]
        return left?.rightBorder == true || right?.leftBorder == true
    }

    private fun visualVerticalSpan(
        sheet: GridSheet,
        initialStart: Int,
        initialEnd: Int,
        colStart: Int,
        colEnd: Int,
        bands: List<TimeBand>,
        styles: Map<Pair<Int, Int>, GridStyleCell>,
    ): IntSpan {
        val day = bands.firstOrNull { initialStart in it.rowStart..it.rowEnd }?.day
            ?: return IntSpan(initialStart, initialEnd)
        val dayBands = bands.filter { it.day == day }
        if (dayBands.isEmpty()) return IntSpan(initialStart, initialEnd)

        val minRow = dayBands.minOf { it.rowStart }
        val maxRow = dayBands.maxOf { it.rowEnd }
        var start = initialStart
        var end = initialEnd

        while (
            start > minRow &&
            !hasFullHorizontalBoundary(sheet, start - 1, colStart, colEnd, styles)
        ) start--

        while (
            end < maxRow &&
            !hasFullHorizontalBoundary(sheet, end, colStart, colEnd, styles)
        ) end++

        return IntSpan(start, end)
    }

    /** Boundary between [upperRow] and upperRow + 1 across the entire visual cell width. */
    private fun hasFullHorizontalBoundary(
        sheet: GridSheet,
        upperRow: Int,
        colStart: Int,
        colEnd: Int,
        styles: Map<Pair<Int, Int>, GridStyleCell>,
    ): Boolean {
        return (colStart..colEnd).all { col ->
            if (sheet.mergedRanges.any {
                    col in it.colStart..it.colEnd && upperRow >= it.rowStart && upperRow < it.rowEnd
                }
            ) {
                false
            } else {
                val upper = styles[upperRow to col]
                val lower = styles[(upperRow + 1) to col]
                upper?.bottomBorder == true || lower?.topBorder == true
            }
        }
    }

    private fun inferStyledParity(
        text: String,
        contentStartRow: Int,
        contentEndRow: Int,
        blockStartRow: Int,
        blockEndRow: Int,
        blockStartHour: Int,
        blockEndHour: Int,
    ): WeekParity {
        val normalized = normalize(text)
        if (Regex("\\bIMPAR\\b").containsMatchIn(normalized)) return WeekParity.ODD
        if (Regex("\\bPAR\\b").containsMatchIn(normalized)) return WeekParity.EVEN

        if (blockStartRow == blockEndRow) return WeekParity.BOTH
        if (blockEndHour - blockStartHour != 2) return WeekParity.BOTH
        if (contentStartRow <= blockStartRow && contentEndRow >= blockEndRow) return WeekParity.BOTH

        val rowCount = blockEndRow - blockStartRow + 1
        val lowerHalfStart = blockStartRow + rowCount / 2
        return when {
            contentEndRow < lowerHalfStart -> WeekParity.ODD
            contentStartRow >= lowerHalfStart -> WeekParity.EVEN
            else -> WeekParity.BOTH
        }
    }

    /**
     * In some first-year series (notably AB), elective course cells are placed only in a subset
     * of group/subgroup columns. The course is still the same course for every student who chose
     * that elective, so scan the whole timetable grid and add only COURSE occurrences globally.
     * Labs/seminars are intentionally not globalized because they may represent alternative groups.
     */
    private fun parseSelectedOptionalCoursesFromWholeGrid(
        sheet: GridSheet,
        selectedCanonical: Set<String>,
        catalog: SubjectCatalog,
        bands: List<TimeBand>,
    ): List<ScheduleSlot> {
        val groups = detectGroups(sheet)
        if (groups.isEmpty()) return emptyList()
        val minCol = groups.minOf { it.startCol }
        val maxCol = groups.maxOf { it.endCol }
        val gridCells = sheet.cells.filter { cell ->
            cell.text.isNotBlank() &&
                !isGenericOptionalPlaceholder(cell.text) &&
                cell.colEnd >= minCol && cell.colStart <= maxCol &&
                bands.any { rangesIntersect(cell.rowStart, cell.rowEnd, it.rowStart, it.rowEnd) }
        }

        val events = mutableListOf<ScheduleSlot>()
        for (cell in gridCells) {
            if (ActivityTextParser.isRoomOnly(cell.text)) continue
            val code = SubjectCatalogParser.extractCode(cell.text) ?: continue
            val canonical = canonicalizeSubjectCode(code)
            if (canonical !in selectedCanonical || !catalog.isOptional(code)) continue

            val styles = sheet.styleCells.associateBy { it.row to it.col }
            var room: String? = null
            var endRow = cell.rowEnd
            var eventStartHour: Int
            var eventEndHour: Int
            var eventDay: String
            var eventParity: WeekParity

            if (styles.isNotEmpty()) {
                val visualColumns = visualHorizontalSpan(
                    sheet, cell.rowStart, cell.colStart, cell.colEnd, minCol, maxCol, styles
                )
                val visualRows = visualVerticalSpan(
                    sheet, cell.rowStart, cell.rowEnd, visualColumns.start, visualColumns.end, bands, styles
                )
                val nextRoom = gridCells
                    .asSequence()
                    .filter { ActivityTextParser.isRoomOnly(it.text) && it.rowStart == cell.rowEnd + 1 }
                    .firstOrNull { candidate ->
                        visualHorizontalSpan(
                            sheet, candidate.rowStart, candidate.colStart, candidate.colEnd, minCol, maxCol, styles
                        ) == visualColumns
                    }
                if (nextRoom != null && nextRoom.rowStart <= visualRows.end) {
                    room = nextRoom.text
                    endRow = maxOf(endRow, nextRoom.rowEnd)
                }

                val firstBand = bands.firstOrNull { visualRows.start in it.rowStart..it.rowEnd } ?: continue
                val lastBand = bands.lastOrNull { visualRows.end in it.rowStart..it.rowEnd } ?: continue
                eventStartHour = resolveEventStartHour(cell, firstBand, lastBand)
                eventEndHour = lastBand.endHour
                eventDay = firstBand.day
                eventParity = inferStyledParity(
                    cell.text, cell.rowStart, endRow, visualRows.start, visualRows.end, firstBand.startHour, lastBand.endHour
                )
            } else {
                if (cell.colStart == cell.colEnd) {
                    val nextRoom = gridCells.firstOrNull { candidate ->
                        candidate.colStart <= cell.colStart && cell.colStart <= candidate.colEnd &&
                            ActivityTextParser.isRoomOnly(candidate.text) &&
                            candidate.rowStart == cell.rowEnd + 1
                    }
                    if (nextRoom != null) {
                        room = nextRoom.text
                        endRow = maxOf(endRow, nextRoom.rowEnd)
                    }
                }

                val firstBandIndex = bands.indexOfFirst { cell.rowStart in it.rowStart..it.rowEnd }
                val lastBandIndex = bands.indexOfLast { endRow in it.rowStart..it.rowEnd }
                if (firstBandIndex < 0 || lastBandIndex < 0) continue
                val firstBand = bands[firstBandIndex]
                val lastBand = bands[lastBandIndex]
                eventStartHour = firstBand.startHour
                eventEndHour = lastBand.endHour
                eventDay = firstBand.day
                eventParity = inferParity(cell.text, cell.rowStart, endRow, firstBandIndex, lastBandIndex, firstBand)
            }

            val activity = ActivityTextParser.parse(cell.text, room, 0, catalog)
            if (activity.type != ro.upb.orarreader.model.ActivityType.COURSE) continue

            events += ScheduleSlot(
                day = eventDay,
                startHour = eventStartHour,
                endHour = eventEndHour,
                parity = eventParity,
                activities = listOf(activity.copy(subgroupIndex = 0, optional = true)),
            )
        }
        return mergeSlots(events).distinct()
    }

    private fun shouldKeepActivity(
        activity: ScheduleActivity,
        selectedCanonical: Set<String>,
        catalog: SubjectCatalog,
    ): Boolean {
        val canonical = activity.code?.let(::canonicalizeSubjectCode)
        val isOptional = activity.optional || catalog.isOptional(activity.code)
        if (!isOptional) return true
        if (canonical.isNullOrBlank()) return false // generic "cursuri opționale conform legendei"
        return canonical in selectedCanonical
    }

    /**
     * Elective legends do not map seminar/lab allocations to 3-digit groups. A listed
     * seminar can therefore be impossible for a specific group even when it is the only
     * seminar time written for that elective. Keep a legend seminar/lab only when it does
     * not overlap a required activity of the selected group in the same week parity.
     *
     * Example: 312 CA already has mandatory PL on Thursday 14-16 odd, so the Antropologie
     * seminar listed at Thursday 14-16 odd cannot belong to that group. The Ant course at
     * 16-18 odd remains valid.
     */
    private fun conflictsWithRequiredSchedule(
        candidate: ScheduleSlot,
        existingSlots: List<ScheduleSlot>,
    ): Boolean = existingSlots.any { existing ->
        existing.day == candidate.day &&
            paritiesOverlap(existing.parity, candidate.parity) &&
            timesOverlap(existing.startHour, existing.endHour, candidate.startHour, candidate.endHour) &&
            existing.activities.any { !it.optional }
    }

    private fun paritiesOverlap(a: WeekParity, b: WeekParity): Boolean =
        a == WeekParity.BOTH || b == WeekParity.BOTH || a == b

    private fun timesOverlap(startA: Int, endA: Int, startB: Int, endB: Int): Boolean =
        maxOf(startA, startB) < minOf(endA, endB)

    private fun mergeSlots(input: List<ScheduleSlot>): List<ScheduleSlot> {
        return input
            .groupBy { SlotIdentity(it.day, it.startHour, it.endHour, it.parity) }
            .map { (key, values) ->
                ScheduleSlot(
                    day = key.day,
                    startHour = key.startHour,
                    endHour = key.endHour,
                    parity = key.parity,
                    activities = values.flatMap { it.activities }.distinct(),
                )
            }
    }

    /**
     * Most two-row time bands in the ACS timetable use their upper/lower physical rows for
     * odd/even week variants, so a row inside a band must normally keep the band's full time.
     *
     * There is one different visual pattern: an activity can start in the middle of a multi-hour
     * band and its own merged cell continues into the following time band. AA Wednesday ISO is
     * encoded this way: the 13-15 label spans rows 38-39, while ISO starts on row 39 and continues
     * into the 15-16 row, which means 14-16.
     *
     * Only adjust the start time when the activity cell itself crosses the band's lower edge and
     * the band's physical rows map exactly 1:1 to hours. This keeps ordinary odd/even half-cells
     * untouched.
     */
    private fun resolveEventStartHour(
        subjectCell: GridCell,
        firstBand: TimeBand,
        lastBand: TimeBand,
    ): Int {
        if (firstBand == lastBand) return firstBand.startHour
        if (firstBand.day != lastBand.day) return firstBand.startHour
        if (subjectCell.rowStart <= firstBand.rowStart) return firstBand.startHour
        if (subjectCell.rowEnd <= firstBand.rowEnd) return firstBand.startHour

        val physicalRowCount = firstBand.rowEnd - firstBand.rowStart + 1
        val durationHours = firstBand.endHour - firstBand.startHour
        if (physicalRowCount <= 1 || durationHours != physicalRowCount) return firstBand.startHour

        val rowOffset = subjectCell.rowStart - firstBand.rowStart
        return (firstBand.startHour + rowOffset).coerceIn(firstBand.startHour, firstBand.endHour)
    }

    private fun inferParity(
        text: String,
        eventStartRow: Int,
        eventEndRow: Int,
        firstBandIndex: Int,
        lastBandIndex: Int,
        band: TimeBand,
    ): WeekParity {
        val normalized = normalize(text)
        if (Regex("\\bIMPAR\\b").containsMatchIn(normalized)) return WeekParity.ODD
        if (Regex("\\bPAR\\b").containsMatchIn(normalized)) return WeekParity.EVEN

        if (firstBandIndex != lastBandIndex || band.rowStart == band.rowEnd) return WeekParity.BOTH

        return when {
            eventStartRow <= band.rowStart && eventEndRow >= band.rowEnd -> WeekParity.BOTH
            eventStartRow <= band.rowStart && eventEndRow < band.rowEnd -> WeekParity.ODD
            eventStartRow > band.rowStart && eventEndRow >= band.rowEnd -> WeekParity.EVEN
            else -> WeekParity.BOTH
        }
    }

    private fun detectTimeBands(sheet: GridSheet): List<TimeBand> {
        val timeCells = sheet.cells.mapNotNull { cell ->
            val match = timeRegex.matchEntire(cell.text.trim()) ?: return@mapNotNull null
            val start = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val end = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            Triple(cell, start, end)
        }
        if (timeCells.isEmpty()) return emptyList()

        val timeColumn = timeCells
            .groupingBy { it.first.colStart }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: return emptyList()

        val dayLabels = sheet.cells
            .filter { normalize(it.text) in normalizedDays }
            .sortedBy { it.rowStart }
            .map { prettyDay(it.text) }
            .distinct()

        val sortedTimes = timeCells
            .filter { it.first.colStart == timeColumn }
            .sortedBy { it.first.rowStart }

        var dayIndex = 0
        var previousStart: Int? = null

        return sortedTimes.map { (cell, start, end) ->
            if (previousStart != null && start < previousStart!!) dayIndex++
            previousStart = start

            TimeBand(
                rowStart = cell.rowStart,
                rowEnd = cell.rowEnd,
                startHour = start,
                endHour = end,
                day = dayLabels.getOrNull(dayIndex)
                    ?: fallbackDays.getOrNull(dayIndex)
                    ?: "ZI ${dayIndex + 1}",
            )
        }
    }

    private fun isGenericOptionalPlaceholder(text: String): Boolean {
        val normalized = normalize(text)
        return normalized.startsWith("CURS OPTIONAL ") ||
            normalized.startsWith("CURSURI OPTIONALE") ||
            normalized.startsWith("CURSURI SI LABORATOARE OPTIONALE") ||
            normalized.startsWith("LABORATOARE OPTIONALE")
    }

    private fun shouldIgnore(text: String): Boolean {
        val value = text.trim()
        if (value.isBlank()) return true
        if (timeRegex.matches(value)) return true
        if (groupRegex.matches(value)) return true
        if (normalize(value) in normalizedDays) return true

        return when (normalize(value)) {
            "DISCIPLINA SI SALA", "ZIUA", "ORA", "ORAR" -> true
            else -> false
        }
    }

    private fun mergeAdjacentIdenticalSlots(input: List<ScheduleSlot>): List<ScheduleSlot> {
        if (input.isEmpty()) return input
        val out = mutableListOf<ScheduleSlot>()

        for (slot in input) {
            val previous = out.lastOrNull()
            if (
                previous != null &&
                previous.day == slot.day &&
                previous.endHour == slot.startHour &&
                previous.parity == slot.parity &&
                previous.activities == slot.activities
            ) {
                out[out.lastIndex] = previous.copy(endHour = slot.endHour)
            } else {
                out += slot
            }
        }

        return out
    }

    private fun rangesIntersect(a1: Int, a2: Int, b1: Int, b2: Int): Boolean = a1 <= b2 && b1 <= a2

    private fun prettyDay(raw: String): String {
        return when (normalize(raw)) {
            "LUNI" -> "LUNI"
            "MARTI" -> "MARȚI"
            "MIERCURI" -> "MIERCURI"
            "JOI" -> "JOI"
            "VINERI" -> "VINERI"
            "SAMBATA" -> "SÂMBĂTĂ"
            else -> raw.trim().uppercase()
        }
    }

    private fun cleanWhitespace(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    private fun normalize(value: String): String {
        return Normalizer.normalize(value.trim().uppercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('Ş', 'S')
            .replace('Ț', 'T')
            .replace('Ţ', 'T')
            .replace(Regex("\\s+"), " ")
    }

    private data class IntSpan(val start: Int, val end: Int)

    private data class TimeBand(
        val rowStart: Int,
        val rowEnd: Int,
        val startHour: Int,
        val endHour: Int,
        val day: String,
    )

    private data class RawEvent(
        val day: String,
        val startHour: Int,
        val endHour: Int,
        val parity: WeekParity,
        val subgroupIndex: Int,
        val subjectRaw: String,
        val roomRaw: String?,
        val rowStart: Int,
        val rowEnd: Int,
    )

    private data class EventIdentity(
        val day: String,
        val startHour: Int,
        val endHour: Int,
        val parity: WeekParity,
        val subjectRaw: String,
        val roomRaw: String,
        val rowStart: Int,
        val rowEnd: Int,
    )

    private data class ParsedEvent(
        val day: String,
        val startHour: Int,
        val endHour: Int,
        val parity: WeekParity,
        val activity: ScheduleActivity,
    )

    private data class SlotIdentity(
        val day: String,
        val startHour: Int,
        val endHour: Int,
        val parity: WeekParity,
    )
}
