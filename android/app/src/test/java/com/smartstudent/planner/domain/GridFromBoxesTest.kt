package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class GridFromBoxesTest {

    /** Real output of a text recogniser (word, position) for images of timetables made for these tests. */
    private fun load(name: String): List<OcrBox> =
        javaClass.getResourceAsStream("/ocr/$name.tsv")!!.bufferedReader().readLines().drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 12) return@mapNotNull null
            val confidence = f[10].toDoubleOrNull() ?: -1.0
            val text = f[11]
            if (text.isBlank() || confidence < 0) return@mapNotNull null
            val left = f[6].toInt()
            val top = f[7].toInt()
            OcrBox(text, left, top, left + f[8].toInt(), top + f[9].toInt())
        }

    private fun rowStartingWith(rows: List<List<String>>, first: String) = rows.first { it.first() == first }

    // ------------------------------------------------------ real recogniser output

    @Test
    fun collegeTimetable_daysDownTheSide_withMergedPeriods() {
        val rows = GridFromBoxes.reconstruct(load("A_clean"))
        assertEquals(
            listOf("DAY", "9:00-9:50", "9:50-10:40", "10:50-11:40", "11:40-12:30", "LUNCH", "1:30-2:20", "2:20-3:10"),
            rowStartingWith(rows, "DAY"),
        )
        // "CS Lab" is one merged cell over two periods: it appears in both columns
        assertEquals(listOf("Monday", "Maths", "Physics", "CS Lab", "CS Lab", "", "DBMS", "DBMS"), rowStartingWith(rows, "Monday"))
        assertEquals(listOf("Tuesday", "Physics", "Maths", "DBMS", "DBMS", "", "os", "os"), rowStartingWith(rows, "Tuesday"))
        assertEquals(listOf("Friday", "Maths", "Maths", "Physics", "os", "", "", ""), rowStartingWith(rows, "Friday"))
    }

    @Test
    fun theTitleLineDoesNotBecomeATableRow() {
        val rows = GridFromBoxes.reconstruct(load("A_clean"))
        val title = rows.first()
        assertTrue(title.drop(1).all { it.isEmpty() })
        assertTrue(title.first().contains("TIME"))
    }

    @Test
    fun timetableWithDaysAcrossTheTop() {
        val rows = GridFromBoxes.reconstruct(load("B_clean"))
        assertEquals(listOf("Time", "Mon", "Tue", "Wed", "Thu", "Fri"), rows.first())
        assertEquals(listOf("9:00 - 10:00", "Maths", "Physics", "Maths", "os", "DBMS"), rows[1])
        assertEquals(listOf("1:00 - 2:00", "Physics", "os", "Maths", "DBMS", "Library"), rows.last())
    }

    @Test
    fun smallText() {
        val rows = GridFromBoxes.reconstruct(load("C_small"))
        assertEquals(listOf("Monday", "Maths", "Physics", "CS Lab", "", "", "DBMS", ""), rowStartingWith(rows, "Monday"))
    }

    @Test
    fun aTiltedBlurryPhotoStillGivesOneRowPerDay() {
        val rows = GridFromBoxes.reconstruct(load("A_photo"))
        for (day in listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday")) {
            assertTrue("missing $day", rows.any { it.first() == day })
        }
        assertEquals("Maths", rowStartingWith(rows, "Monday")[1])
        assertEquals("CS Lab", rowStartingWith(rows, "Wednesday")[1])
    }

    // ---------------------------------------------------------------- synthetic cases

    /** A perfect grid: [lefts] are the columns' left edges, every column is [colWidth] wide. */
    private fun grid(rows: List<List<String>>, lefts: List<Int>, colWidth: Int = 150, rowHeight: Int = 70, top: Int = 100): List<OcrBox> {
        val out = mutableListOf<OcrBox>()
        rows.forEachIndexed { r, cells ->
            cells.forEachIndexed { c, text ->
                if (text.isBlank()) return@forEachIndexed
                val words = text.split(" ")
                val wordWidths = words.map { it.length * 12 }
                val total = wordWidths.sum() + (words.size - 1) * 8
                var x = lefts[c] + (colWidth - total) / 2
                words.forEachIndexed { i, w ->
                    out += OcrBox(w, x, top + r * rowHeight, x + wordWidths[i], top + r * rowHeight + 20)
                    x += wordWidths[i] + 8
                }
            }
        }
        return out
    }

    private val lefts = listOf(40, 200, 360, 520, 680)
    private val sample = listOf(
        listOf("DAY", "9:00-9:50", "9:50-10:40", "10:50-11:40", "11:40-12:30"),
        listOf("Monday", "Maths", "Physics", "Chemistry", "Biology"),
        listOf("Tuesday", "Physics", "Maths", "", "English"),
        listOf("Wednesday", "Lab", "", "Maths", "Physics"),
    )

    @Test
    fun aPerfectGridComesBackUnchanged() {
        assertEquals(sample, GridFromBoxes.reconstruct(grid(sample, lefts)))
    }

    @Test
    fun theOrderOfTheWordsDoesNotMatter() {
        val boxes = grid(sample, lefts).shuffled(java.util.Random(3))
        assertEquals(sample, GridFromBoxes.reconstruct(boxes))
    }

    @Test
    fun aTiltedPhotoIsStraightenedFirst() {
        for (degrees in listOf(-3.0, -1.5, 1.2, 2.5)) {
            val rad = Math.toRadians(degrees)
            val tilted = grid(sample, lefts).map { b ->
                val cx = (b.left + b.right) / 2.0
                val cy = (b.top + b.bottom) / 2.0
                val nx = cx * cos(rad) - cy * sin(rad)
                val ny = cx * sin(rad) + cy * cos(rad)
                OcrBox(b.text, (nx - b.width / 2).toInt(), (ny - b.height / 2).toInt(), (nx + b.width / 2).toInt(), (ny + b.height / 2).toInt())
            }
            assertEquals("tilt $degrees", sample, GridFromBoxes.reconstruct(tilted))
        }
    }

    @Test
    fun aMergedCellCentredOverTwoColumnsIsRepeatedInBoth() {
        val rows = listOf(
            listOf("DAY", "9:00-9:50", "9:50-10:40", "10:50-11:40", "11:40-12:30"),
            listOf("Monday", "Maths", "", "", "Physics"),
        )
        val boxes = grid(rows, lefts).toMutableList()
        // "CS Lab" sits across the boundary between the 3rd and 4th columns
        val boundary = (lefts[2] + 150 + lefts[3]) / 2
        boxes += OcrBox("CS", boundary - 40, 170, boundary - 8, 190)
        boxes += OcrBox("Lab", boundary + 4, 170, boundary + 40, 190)
        val result = GridFromBoxes.reconstruct(boxes)
        assertEquals(listOf("Monday", "Maths", "", "CS Lab", "CS Lab").size, result[1].size)
        assertEquals("CS Lab", result[1][2])
        assertEquals("CS Lab", result[1][3])
        assertEquals("Physics", result[1][4])
    }

    @Test
    fun twoTimeRangesFusedByABorderAreSplit() {
        val header = listOf(
            OcrBox("DAY", 60, 100, 110, 120),
            OcrBox("9:00-9:50", 205, 100, 305, 120),
            OcrBox("|9:50-10:40", 360, 100, 490, 120),
            OcrBox("10:50-11:40/11:40-12:30", 520, 100, 810, 120), // two header cells read as one word
        )
        val body = grid(listOf(listOf("", "", "", "", ""), listOf("Monday", "Maths", "Physics", "Chemistry", "Biology"), listOf("Tuesday", "OS", "DBMS", "Lab", "Lab")), lefts)
        val result = GridFromBoxes.reconstruct(header + body)
        assertEquals(listOf("DAY", "9:00-9:50", "9:50-10:40", "10:50-11:40", "11:40-12:30"), result.first { it.first() == "DAY" })
    }

    @Test
    fun theBorderLinesReadAsGarbageAreIgnored() {
        val boxes = grid(sample, lefts).toMutableList()
        boxes += OcrBox("T", 335, 130, 346, 175)   // a thin, tall "character": a vertical border line
        boxes += OcrBox("|", 190, 170, 193, 230)
        boxes += OcrBox("+", 500, 135, 510, 145)
        assertEquals(sample, GridFromBoxes.reconstruct(boxes))
    }

    @Test
    fun aLostDashInATimeRangeIsRepaired() {
        val rows = listOf(
            listOf("Time", "Mon", "Tue", "Wed"),
            listOf("9:00 10:00", "Maths", "Physics", "Maths"),
            listOf("10:00 - 11:00", "Maths", "Chemistry", "Lab"),
        )
        val result = GridFromBoxes.reconstruct(grid(rows, listOf(40, 220, 380, 540), colWidth = 150))
        assertEquals("9:00 - 10:00", result[1][0])
    }

    @Test
    fun nothingRecognisedGivesNothing() {
        assertTrue(GridFromBoxes.reconstruct(emptyList()).isEmpty())
        assertTrue(GridFromBoxes.reconstruct(listOf(OcrBox("|", 1, 1, 3, 40))).isEmpty())
    }

    @Test
    fun plainTextThatIsNotATableComesBackAsLines() {
        val boxes = listOf(
            OcrBox("Hello", 10, 10, 80, 30), OcrBox("world", 90, 10, 160, 30),
            OcrBox("Second", 10, 60, 90, 80), OcrBox("line", 100, 60, 150, 80),
        )
        assertEquals(listOf(listOf("Hello world"), listOf("Second line")), GridFromBoxes.reconstruct(boxes))
    }
}
