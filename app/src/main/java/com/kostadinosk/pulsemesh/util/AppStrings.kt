// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

/**
 * Lightweight bilingual UI localization helper for English (`en`) and Greek (`el`).
 * Keeps Compose UI screens cleanly localized at runtime without restarting the activity.
 */
object AppStrings {
    fun tr(lang: String, en: String, el: String): String =
        if (lang.equals("el", ignoreCase = true)) el else en

    fun weatherTrendLabel(lang: String, rawLabel: String): String {
        if (!lang.equals("el", ignoreCase = true)) return rawLabel
        return when (rawLabel.uppercase()) {
            "STORM WARNING" -> "ΠΡΟΕΙΔΟΠΟΙΗΣΗ ΚΑΤΑΙΓΙΔΑΣ"
            "FALLING" -> "ΠΤΩΣΗ"
            "RISING" -> "ΑΝΟΔΟΣ"
            "STABLE" -> "ΣΤΑΘΕΡΟΣ"
            "UNAVAILABLE" -> "ΜΗ ΔΙΑΘΕΣΙΜΟ"
            else -> rawLabel
        }
    }
}
