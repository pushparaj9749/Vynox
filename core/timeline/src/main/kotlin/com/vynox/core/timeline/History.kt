package com.vynox.core.timeline

import com.vynox.core.model.VynoxProject

/**
 * One undoable change: a label plus the project before and after.
 *
 * Projects are metadata only (assets are referenced, never embedded in the
 * snapshot), so snapshotting is cheap and completely avoids "half applied"
 * inverse operations - every editor operation, no matter how complex, is
 * undoable by construction.
 */
data class HistoryEntry(
    val label: String,
    val before: VynoxProject,
    val after: VynoxProject,
    val coalesceKey: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Undo/redo stack with drag coalescing.
 *
 * Dragging a clip or a slider produces hundreds of intermediate states; passing
 * the same [coalesceKey] collapses them into a single undo step.
 */
class History(
    initial: VynoxProject,
    private val limit: Int = 250,
    private val coalesceWindowMs: Long = 700L
) {

    private val undoStack = ArrayDeque<HistoryEntry>()
    private val redoStack = ArrayDeque<HistoryEntry>()

    var current: VynoxProject = initial
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label
    val undoDepth: Int get() = undoStack.size

    /**
     * Records [next] as the current state.
     * Returns true when a new entry was pushed (false when coalesced or no-op).
     */
    fun commit(label: String, next: VynoxProject, coalesceKey: String? = null): Boolean {
        if (next === current) return false
        val previous = current
        current = next
        redoStack.clear()

        if (coalesceKey != null) {
            val top = undoStack.lastOrNull()
            if (top != null && top.coalesceKey == coalesceKey &&
                System.currentTimeMillis() - top.timestamp < coalesceWindowMs
            ) {
                undoStack.removeLast()
                undoStack.addLast(top.copy(after = next, timestamp = System.currentTimeMillis()))
                return false
            }
        }

        undoStack.addLast(HistoryEntry(label, previous, next, coalesceKey))
        while (undoStack.size > limit) undoStack.removeFirst()
        return true
    }

    /** Replaces the current state without creating an undo entry (e.g. playhead moves). */
    fun replace(project: VynoxProject) {
        current = project
    }

    fun undo(): Boolean {
        val entry = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(entry)
        current = entry.before
        return true
    }

    fun redo(): Boolean {
        val entry = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(entry)
        current = entry.after
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    /** Marks the current state as saved (keeps history for further edits). */
    fun snapshot(): VynoxProject = current
}
