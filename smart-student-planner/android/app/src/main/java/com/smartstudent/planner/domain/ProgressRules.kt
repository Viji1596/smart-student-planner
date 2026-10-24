package com.smartstudent.planner.domain

/** How a task's progress changes when "finished" is taken back. */
object ProgressRules {
    /**
     * A task that was finished and is then un-finished goes back to the progress it had before it was finished
     * ([spentBeforeDone]), or to nothing if that isn't known. It never stays "one minute short", which would leave a
     * pointless one-minute session in the plan.
     */
    fun afterUncomplete(spentBeforeDone: Int?, estimated: Int): Int =
        (spentBeforeDone ?: 0).coerceIn(0, maxOf(0, estimated - 1))

    /** Progress after taking back [minutes] of work that had been recorded as done. */
    fun afterUndo(spent: Int, minutes: Int): Int = (spent - minutes).coerceAtLeast(0)
}
