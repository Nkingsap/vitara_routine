package com.vitara.routine.data

/**
 * The icon key stored with a routine is kept for data compatibility only - the
 * interface no longer draws per-routine icons.
 */
internal fun legacyIconKey(emoji: String?): String = when (emoji?.trim()) {
    "⏰", "🔔" -> "alarm"
    "📚" -> "study"
    "🍳" -> "cooking"
    "🚿" -> "clean"
    "☕" -> "rest"
    "🍽️", "🍽" -> "meal"
    "🏃" -> "exercise"
    "🌙" -> "sleep"
    else -> "note"
}
