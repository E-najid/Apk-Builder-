package com.enajid.apkbuilder.util

import java.util.Locale

object Format {

    fun bytes(bytes: Long): String = when {
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576f)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}
