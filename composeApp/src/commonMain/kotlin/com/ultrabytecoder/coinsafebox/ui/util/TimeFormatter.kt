package com.ultrabytecoder.coinsafebox.ui.util

import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import nl.jacobras.humanreadable.HumanReadable

fun formatTransactionTime(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val now = Clock.System.now()
    val difference = now - instant

    return if (difference < 24.hours) {
        HumanReadable.timeAgo(instant)
    } else {
        val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())
        val month = localDateTime.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
        "${month} ${localDateTime.day}, ${localDateTime.year}"
    }
}
