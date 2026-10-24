package com.smartstudent.planner.domain

/** A class read from an uploaded timetable. day: 1 = Monday ... 7 = Sunday; times are "HH:mm". */
data class ImportedClass(val day: Int, val title: String, val start: String, val end: String)

object TimetableImport {
    private fun key(c: ImportedClass) = "${c.day}|${c.start}|${c.end}|${c.title.trim().lowercase()}"

    /**
     * What to add when the user chooses "add to my timetable": the incoming classes, minus any that are
     * already there (same day, times and title) and minus repeats inside the incoming list itself.
     */
    fun newClasses(existing: List<ImportedClass>, incoming: List<ImportedClass>): List<ImportedClass> {
        val seen = existing.map(::key).toMutableSet()
        val out = mutableListOf<ImportedClass>()
        for (c in incoming) if (seen.add(key(c))) out += c
        return out
    }

    /** How many pairs of classes overlap on the same day (a sign the file was misread). */
    fun overlapCount(classes: List<ImportedClass>): Int {
        var count = 0
        for (day in classes.groupBy { it.day }.values) {
            val sorted = day.sortedBy { it.start }
            for (i in sorted.indices) {
                for (j in i + 1 until sorted.size) {
                    if (sorted[j].start < sorted[i].end) count++ else break
                }
            }
        }
        return count
    }

    /** True if the times are valid "HH:mm" and the class ends after it starts. */
    fun isValid(c: ImportedClass): Boolean {
        val s = TimeUtil.parse(c.start) ?: return false
        val e = TimeUtil.parse(c.end) ?: return false
        return c.day in 1..7 && c.title.isNotBlank() && e > s
    }
}
