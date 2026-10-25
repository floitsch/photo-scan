// Copyright (C) 2026 Toit contributors.
package org.toit.lumascan

/** Pure Camera2 focus policy; values are CameraMetadata AF/lens constants. */
internal object CameraFocusPolicy {
    fun mode(modes: IntArray, fixedFocus: Boolean): Int {
        if (fixedFocus && modes.contains(0)) return 0
        return listOf(4, 3, 1, 2).firstOrNull { modes.contains(it) } ?: 0
    }

    fun continuous(mode: Int) = mode == 4 || mode == 3
    fun triggered(mode: Int) = mode == 1 || mode == 2

    fun settled(mode: Int, afState: Int?, lensState: Int?, fixedFocus: Boolean): Boolean {
        if (lensState == 1 || afState == 5 || afState == 6) return false // Moving or explicitly not focused.
        if (fixedFocus) return true
        // A continuous-mode LOCKED result is stale until CANCEL resumes passive focus.
        return if (continuous(mode)) afState == 2 else triggered(mode) && afState == 4
    }

    fun closestFocusCm(minimumFocusDistance: Float?, calibration: Int?): Double? =
        if (minimumFocusDistance != null && minimumFocusDistance > 0 && calibration != null && calibration != 0)
            100.0 / minimumFocusDistance else null

    fun nearLimit(distance: Float?, minimum: Float?) =
        distance != null && minimum != null && minimum > 0 && distance >= .95f * minimum
}
