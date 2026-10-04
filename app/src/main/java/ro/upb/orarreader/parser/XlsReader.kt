package ro.upb.orarreader.parser

import jxl.Workbook
import jxl.format.Border
import jxl.format.BorderLineStyle
import ro.upb.orarreader.model.GridCell
import ro.upb.orarreader.model.GridRange
import ro.upb.orarreader.model.GridSheet
import ro.upb.orarreader.model.GridStyleCell
import java.io.InputStream

object XlsReader {
    fun read(input: InputStream): GridSheet {
        val workbook = Workbook.getWorkbook(input)
        try {
            if (workbook.numberOfSheets == 0) {
                throw IllegalArgumentException("Fișierul Excel nu conține foi.")
            }

            val sheet = workbook.getSheet(0)
            val mergedRanges = sheet.mergedCells.map { range ->
                GridRange(
                    rowStart = range.topLeft.row,
                    rowEnd = range.bottomRight.row,
                    colStart = range.topLeft.column,
                    colEnd = range.bottomRight.column,
                )
            }
            val mergedByTopLeft = mergedRanges.associateBy { it.rowStart to it.colStart }

            val cells = buildList {
                for (row in 0 until sheet.rows) {
                    for (col in 0 until sheet.columns) {
                        val text = sheet.getCell(col, row).contents.trim()
                        if (text.isBlank()) continue

                        val merged = mergedByTopLeft[row to col]
                        add(
                            GridCell(
                                rowStart = row,
                                rowEnd = merged?.rowEnd ?: row,
                                colStart = col,
                                colEnd = merged?.colEnd ?: col,
                                text = text,
                            )
                        )
                    }
                }
            }

            // ACS timetables encode important semantics in the drawn cell borders. In particular,
            // a two-hour box split over two physical rows can mean odd/even week even when the
            // text itself never says "par" or "impar". Keep the border topology so the parser
            // can reconstruct those visual boxes instead of treating every physical row as a
            // separate one-hour activity.
            val styleCells = buildList {
                for (row in 0 until sheet.rows) {
                    for (col in 0 until sheet.columns) {
                        val format = sheet.getCell(col, row).cellFormat ?: continue
                        val top = format.getBorder(Border.TOP) != BorderLineStyle.NONE
                        val bottom = format.getBorder(Border.BOTTOM) != BorderLineStyle.NONE
                        val left = format.getBorder(Border.LEFT) != BorderLineStyle.NONE
                        val right = format.getBorder(Border.RIGHT) != BorderLineStyle.NONE
                        if (!top && !bottom && !left && !right) continue

                        add(
                            GridStyleCell(
                                row = row,
                                col = col,
                                topBorder = top,
                                bottomBorder = bottom,
                                leftBorder = left,
                                rightBorder = right,
                            )
                        )
                    }
                }
            }

            return GridSheet(
                name = sheet.name,
                cells = cells,
                styleCells = styleCells,
                mergedRanges = mergedRanges,
            )
        } finally {
            workbook.close()
        }
    }
}
