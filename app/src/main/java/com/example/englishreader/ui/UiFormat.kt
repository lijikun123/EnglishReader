package com.example.englishreader.ui

import android.text.format.DateUtils

/** 相对时间，如「3 小时前」。 */
fun formatRelativeTime(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

/** 进度百分比，如 42%。 */
fun formatPercent(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"
