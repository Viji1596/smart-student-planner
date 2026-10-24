package com.smartstudent.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableImportTest {
    private fun c(day: Int, title: String, start: String, end: String) = ImportedClass(day, title, start, end)

    @Test
    fun alreadyPresentClassesAreNotAddedAgain() {
        val existing = listOf(c(1, "Maths", "09:00", "10:00"))
        val incoming = listOf(c(1, "maths ", "09:00", "10:00"), c(1, "Physics", "10:00", "11:00"))
        assertEquals(listOf(c(1, "Physics", "10:00", "11:00")), TimetableImport.newClasses(existing, incoming))
    }

    @Test
    fun repeatsInsideTheFileAreCollapsed() {
        val incoming = listOf(c(2, "OS", "09:00", "10:00"), c(2, "OS", "09:00", "10:00"), c(3, "OS", "09:00", "10:00"))
        assertEquals(2, TimetableImport.newClasses(emptyList(), incoming).size)
    }

    @Test
    fun sameSubjectOnADifferentDayOrTimeIsADifferentClass() {
        val existing = listOf(c(1, "Maths", "09:00", "10:00"))
        val incoming = listOf(c(2, "Maths", "09:00", "10:00"), c(1, "Maths", "10:00", "11:00"))
        assertEquals(2, TimetableImport.newClasses(existing, incoming).size)
    }

    @Test
    fun overlapsAreCounted() {
        val classes = listOf(c(1, "A", "09:00", "10:00"), c(1, "B", "09:30", "10:30"), c(1, "C", "10:30", "11:00"), c(2, "D", "09:00", "10:00"))
        assertEquals(1, TimetableImport.overlapCount(classes))
        assertEquals(0, TimetableImport.overlapCount(listOf(c(1, "A", "09:00", "10:00"), c(1, "B", "10:00", "11:00"))))
    }

    @Test
    fun validation() {
        assertTrue(TimetableImport.isValid(c(1, "Maths", "09:00", "09:50")))
        assertFalse(TimetableImport.isValid(c(8, "Maths", "09:00", "09:50")))
        assertFalse(TimetableImport.isValid(c(1, " ", "09:00", "09:50")))
        assertFalse(TimetableImport.isValid(c(1, "Maths", "10:00", "09:00")))
        assertFalse(TimetableImport.isValid(c(1, "Maths", "9:00", "10:00")))
    }
}
