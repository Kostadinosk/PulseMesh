// Copyright (c) 2026 Konstantinos Kourtis
// SPDX-License-Identifier: GPL-3.0-only

package com.kostadinosk.pulsemesh.util

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Bilingual UI localization helper for English (`en`) and Greek (`el`).
 * Resolves standard Android `strings.xml` / `values-el/strings.xml` resources at runtime
 * via a locale-overridden configuration context without restarting the Activity.
 */
object AppStrings {
    fun tr(lang: String, en: String, el: String): String =
        if (lang.equals("el", ignoreCase = true)) el else en

    fun resolve(
        context: Context,
        lang: String,
        @StringRes resId: Int,
        vararg formatArgs: Any
    ): String {
        val targetLang = if (lang.equals("el", ignoreCase = true)) "el" else "en"
        val localizedContext = runCatching {
            val cfg = Configuration(context.resources.configuration)
            cfg.setLocale(Locale(targetLang))
            context.createConfigurationContext(cfg)
        }.getOrDefault(context)
        return runCatching {
            if (formatArgs.isEmpty()) localizedContext.getString(resId)
            else localizedContext.getString(resId, *formatArgs)
        }.getOrElse {
            runCatching {
                if (formatArgs.isEmpty()) context.getString(resId)
                else context.getString(resId, *formatArgs)
            }.getOrDefault("")
        }
    }

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
