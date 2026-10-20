package com.smartstudent.planner.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** A piece of recognised text and where it is in the image (pixels). */
data class OcrBox(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Turns the words a text recogniser found in a photo of a timetable back into a grid of rows and
 * columns, which the server's grid reader then understands (the same one used for Excel files).
 * Pure Kotlin so it can be tested on the JVM.
 *
 * Steps: clean up border junk and split fused tokens -> straighten a slightly tilted photo ->
 * group words into rows -> join neighbouring words into cells -> find the columns from the empty
 * vertical gaps -> place each cell in its column (a wide cell that spans several columns is repeated
 * in each, the way a merged Excel cell is).
 */
object GridFromBoxes {
    private val TIME_RANGE = Regex(
        """\d{1,2}[:.]\d{2}\s*(?:am|pm|a\.m\.|p\.m\.)?\s*[-–—]\s*\d{1,2}[:.]\d{2}(?:\s*(?:am|pm|a\.m\.|p\.m\.))?""",
        RegexOption.IGNORE_CASE,
    )
    private val FULL_TIME_RANGE = Regex(
        """^\d{1,2}[:.]\d{2}\s*(?:am|pm|a\.m\.|p\.m\.)?\s*[-–—]\s*\d{1,2}[:.]\d{2}\s*(?:am|pm|a\.m\.|p\.m\.)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val SINGLE_TIME = Regex("""^\d{1,2}[:.]\d{2}\s*(?:am|pm)?$""", RegexOption.IGNORE_CASE)
    private val DAY_NAME = Regex(
        "^(mon|monday|tue|tues|tuesday|wed|weds|wednesday|thu|thur|thurs|thursday|fri|friday|sat|saturday|sun|sunday)\\.?$",
        RegexOption.IGNORE_CASE,
    )
    private val LOST_DASH = Regex("""^(\d{1,2}[:.]\d{2})\s+(\d{1,2}[:.]\d{2})$""")
    private val NOT_TIME_CHARS = Regex("[^0-9:.\\-–—apmAPM\\s]")
    private const val EDGE_NOISE = "|!¦[]{}_~\\/"
    private const val LONE_PUNCTUATION = ".,:;'`\""
    private const val DASHES = "-–—"

    private class Pt(val text: String, var x: Double, var y: Double, val width: Double, val height: Double)

    /** A time range, a start time or a day name is always a whole cell: never joined to its neighbour. */
    private fun isStandalone(text: String): Boolean {
        val t = text.trim()
        return FULL_TIME_RANGE.matches(t) || SINGLE_TIME.matches(t) || DAY_NAME.matches(t)
    }

    private class Cell(var text: String, var left: Double, var right: Double, var y: Double) {
        val width: Double get() = right - left
        val centre: Double get() = (left + right) / 2
    }

    fun reconstruct(raw: List<OcrBox>): List<List<String>> {
        val cleaned = clean(raw)
        if (cleaned.isEmpty()) return emptyList()

        val typicalHeight = median(cleaned.map { it.height.toDouble() }).coerceAtLeast(6.0)
        // A table border can be read as a stray character ("T", "4", "+"): it is tall and very thin.
        val boxes = cleaned.filterNot { it.text.length <= 2 && (it.width < 0.35 * it.height || it.height > 2.2 * typicalHeight) }
        if (boxes.isEmpty()) return emptyList()

        val h = median(boxes.map { it.height.toDouble() }).coerceAtLeast(6.0)
        val points = straighten(boxes.map { Pt(it.text, (it.left + it.right) / 2.0, (it.top + it.bottom) / 2.0, it.width.toDouble(), it.height.toDouble()) }, h)

        val rows = groupRows(points, h).map { toCells(it, h) }
        val tableRows = rows.filter { it.size >= 3 }
        if (tableRows.size < 2) {
            return rows.map { r -> r.map { it.text } }
        }

        // The header row (days, or time ranges) marks the columns reliably, even when a merged cell's
        // text sits on a column boundary. Without a header, fall back to the empty gaps between texts.
        val anchored = anchorColumns(tableRows)
        val medianWidth = median(tableRows.flatten().map { it.width })
        val isWide = { c: Cell -> c.width > 1.7 * medianWidth }
        val columns = anchored ?: findColumns(tableRows, isWide, h)
        if (columns.isEmpty()) return rows.map { r -> r.map { it.text } }

        val out = mutableListOf<List<String>>()
        for (row in rows) {
            val line = Array(columns.size) { "" }
            if (row.size < 3) {
                line[0] = row.joinToString(" ") { it.text } // a title or a stray line
            } else {
                for (cell in row) {
                    val targets = columnsFor(columns, cell, anchored != null, isWide(cell))
                    for (i in targets) line[i] = if (line[i].isEmpty()) cell.text else line[i] + " " + cell.text
                }
            }
            // a "9:00 10:00" cell is a range whose dash the recogniser missed
            for (i in line.indices) {
                line[i] = LOST_DASH.replace(line[i]) { "${it.groupValues[1]} - ${it.groupValues[2]}" }
            }
            if (line.any { it.isNotBlank() } && !isNoiseRow(line)) out += line.toList()
        }
        return out
    }

    /** A row with only one or two tiny fragments ("T", "4", "+") is just table border noise. */
    private fun isNoiseRow(line: Array<String>): Boolean {
        val filled = line.filter { it.isNotBlank() }
        return filled.size <= 2 && filled.all { it.trim().length <= 2 }
    }

    // ------------------------------------------------------------------ cleaning

    private fun clean(raw: List<OcrBox>): List<OcrBox> {
        val out = mutableListOf<OcrBox>()
        for (b in raw) {
            val t = b.text.trim().trim { it in EDGE_NOISE }.trim()
            if (t.isEmpty() || b.width <= 0 || b.height <= 0) continue

            // Two time ranges glued together by a border line ("10:50-11:40/11:40-12:30", or with the
            // border read as letters: "h0:50-11:40h1:40-12:30"). Letters and symbols that can't be part of
            // a time are blanked out (same length, so positions still line up) before looking for ranges.
            val timeOnly = t.replace(NOT_TIME_CHARS, " ")
            val ranges = TIME_RANGE.findAll(timeOnly).toList()
            if (ranges.size >= 2) {
                val total = t.length.toDouble()
                for (m in ranges) {
                    val l = b.left + (b.width * m.range.first / total).roundToInt()
                    val r = b.left + (b.width * (m.range.last + 1) / total).roundToInt()
                    out += OcrBox(m.value.trim(), l, b.top, max(r, l + 1), b.bottom)
                }
                continue
            }
            if (t.length == 1 && t[0] in LONE_PUNCTUATION) continue
            out += b.copy(text = t)
        }
        return out
    }

    // ----------------------------------------------------------------- straightening

    /** Photos are rarely level. Finds the small rotation that makes the rows line up best and applies it. */
    private fun straighten(points: List<Pt>, h: Double): List<Pt> {
        fun score(deg: Double): Int {
            val rad = deg * PI / 180
            val bins = HashMap<Int, Int>()
            for (p in points) {
                val y = -p.x * sin(rad) + p.y * cos(rad)
                bins.merge(floor(y / (h / 2)).toInt(), 1, Int::plus)
            }
            return bins.values.sumOf { it * it }
        }

        val base = score(0.0)
        var best = 0.0
        var bestScore = base
        var deg = -4.0
        while (deg <= 4.0) {
            val s = score(deg)
            if (s > bestScore) {
                bestScore = s
                best = deg
            }
            deg += 0.25
        }
        if (best == 0.0 || bestScore < base * 1.05) return points

        val rad = best * PI / 180
        return points.map { Pt(it.text, it.x * cos(rad) + it.y * sin(rad), -it.x * sin(rad) + it.y * cos(rad), it.width, it.height) }
    }

    // ---------------------------------------------------------------------- rows and cells

    private fun groupRows(points: List<Pt>, h: Double): List<List<Pt>> {
        val rows = mutableListOf<MutableList<Pt>>()
        var meanY = 0.0
        for (p in points.sortedBy { it.y }) {
            val current = rows.lastOrNull()
            if (current != null && abs(p.y - meanY) <= 0.6 * h) {
                current += p
                meanY = current.sumOf { it.y } / current.size
            } else {
                rows += mutableListOf(p)
                meanY = p.y
            }
        }
        return rows
    }

    /** Joins words that sit close together into one cell ("CS" + "Lab" -> "CS Lab"; "9:00" "-" "10:00" -> one range). */
    private fun toCells(row: List<Pt>, h: Double): List<Cell> {
        // Judge distances by this row's own text size: a title line has bigger letters, and bigger gaps.
        val rowHeight = median(row.map { it.height }).coerceAtLeast(h * 0.5)
        val cells = mutableListOf<Cell>()
        for (p in row.sortedBy { it.x }) {
            val left = p.x - p.width / 2
            val right = p.x + p.width / 2
            val last = cells.lastOrNull()
            val isDash = p.text.length == 1 && p.text[0] in DASHES
            val lastEndsInDash = last != null && last.text.isNotEmpty() && last.text.last() in DASHES
            val timeThenDash = isDash && last != null && SINGLE_TIME.matches(last.text.trim())
            val dashThenTime = lastEndsInDash && SINGLE_TIME.matches(p.text.trim())
            val gap = if (last != null) left - last.right else Double.MAX_VALUE
            val plainJoin = last != null && !isStandalone(p.text) && !isStandalone(last.text) && !isDash && gap <= 0.8 * rowHeight
            val rangeJoin = (timeThenDash || dashThenTime) && gap <= 1.6 * rowHeight

            if (last != null && (plainJoin || rangeJoin)) {
                last.text += (if (timeThenDash) " " else " ") + p.text
                last.right = max(last.right, right)
            } else {
                cells += Cell(p.text, left, right, p.y)
            }
        }
        return cells
    }

    // ---------------------------------------------------------------------------- columns

    private class Column(val start: Double, val end: Double) {
        val centre: Double get() = (start + end) / 2
    }

    /** Columns are the stretches of the page where text appears; the empty vertical strips are the gaps between them. */
    private fun findColumns(tableRows: List<List<Cell>>, isWide: (Cell) -> Boolean, h: Double): List<Column> {
        val narrow = tableRows.flatten().filterNot(isWide)
        if (narrow.isEmpty()) return emptyList()
        val minX = floor(narrow.minOf { it.left }).toInt()
        val maxX = ceil(narrow.maxOf { it.right }).toInt()
        val size = min(maxX - minX + 1, 8000)
        if (size <= 1) return emptyList()

        val coverage = IntArray(size)
        for (c in narrow) {
            val a = (floor(c.left).toInt() - minX).coerceIn(0, size - 1)
            val b = (ceil(c.right).toInt() - minX).coerceIn(0, size - 1)
            for (x in a..b) coverage[x]++
        }

        val minGap = max(3.0, 0.3 * h).toInt()
        val columns = mutableListOf<Column>()
        var x = 0
        while (x < size) {
            if (coverage[x] == 0) {
                x++
                continue
            }
            var end = x
            var gap = 0
            var cursor = x
            // extend until an empty strip at least as wide as a gap
            while (cursor < size) {
                if (coverage[cursor] > 0) {
                    end = cursor
                    gap = 0
                } else {
                    gap++
                    if (gap >= minGap) break
                }
                cursor++
            }
            columns += Column((x + minX).toDouble(), (end + minX).toDouble())
            x = end + 1
        }
        return columns
    }

    /** The columns of the header row: every cell of the row with the most times or day names. */
    private fun anchorColumns(tableRows: List<List<Cell>>): List<Column>? {
        val header = tableRows.maxByOrNull { row -> row.count { isStandalone(it.text) } } ?: return null
        if (header.count { isStandalone(it.text) } < 3) return null
        return header.map { Column(it.left, it.right) }
    }

    /**
     * Which column(s) a cell belongs to. Usually one. A cell that sits across a column boundary
     * (a merged cell, text centred over two periods) is repeated in each column it covers, the way a
     * merged Excel cell is.
     */
    private fun columnsFor(columns: List<Column>, cell: Cell, byHeader: Boolean, wide: Boolean): List<Int> {
        if (byHeader) {
            val width = max(cell.width, 1.0)
            val covered = columns.indices.filter {
                val overlap = min(cell.right, columns[it].end) - max(cell.left, columns[it].start)
                overlap >= 0.2 * width
            }
            return covered.ifEmpty { listOf(columnOf(columns, cell.centre)) }
        }
        if (wide) {
            val covered = columns.indices.filter { columns[it].centre in cell.left..cell.right }
            return covered.ifEmpty { listOf(columnOf(columns, cell.centre)) }
        }
        return listOf(columnOf(columns, cell.centre))
    }

    private fun columnOf(columns: List<Column>, x: Double): Int {
        columns.forEachIndexed { i, c -> if (x >= c.start && x <= c.end) return i }
        return columns.indices.minByOrNull { abs(columns[it].centre - x) } ?: 0
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
