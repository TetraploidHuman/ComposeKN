/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(kotlin.time.ExperimentalTime::class)

package androidx.compose.material3.internal

import androidx.compose.material3.CalendarLocale
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.FormatStringsInDatetimeFormats
import kotlinx.datetime.format.byUnicodePattern
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private val weekdayNamesEn = listOf(
    "S" to "Sunday",
    "M" to "Monday",
    "T" to "Tuesday",
    "W" to "Wednesday",
    "T" to "Thursday",
    "F" to "Friday",
    "S" to "Saturday",
)

internal actual class PlatformDateFormat actual constructor(@Suppress("UNUSED_PARAMETER") locale: CalendarLocale) {
    actual val firstDayOfWeek: Int = 1

    actual val weekdayNames: List<Pair<String, String>> = weekdayNamesEn

    @OptIn(FormatStringsInDatetimeFormats::class)
    actual fun formatWithPattern(
        utcTimeMillis: Long,
        pattern: String,
        cache: MutableMap<String, Any>,
    ): String {
        val date = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC).date
        return date.format(LocalDate.Format { byUnicodePattern(pattern) })
    }

    actual fun formatWithSkeleton(
        utcTimeMillis: Long,
        skeleton: String,
        cache: MutableMap<String, Any>,
    ): String = formatWithPattern(utcTimeMillis, skeleton, cache)

    actual fun parse(
        date: String,
        pattern: String,
        locale: CalendarLocale,
        cache: MutableMap<String, Any>,
    ): CalendarDate? {
        return try {
            val localDate = LocalDate.parse(date, LocalDate.Format { byUnicodePattern(pattern) })
            CalendarDate(
                year = localDate.year,
                month = localDate.monthNumber,
                dayOfMonth = localDate.dayOfMonth,
                utcTimeMillis = utcTimeMillis(localDate),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    actual fun getDateInputFormat(): DateInputFormat =
        DateInputFormat(patternWithDelimiters = "MM/dd/yyyy", delimiter = '/')

    actual fun is24HourFormat(): Boolean = true

    @OptIn(FormatStringsInDatetimeFormats::class)
    private fun utcTimeMillis(date: LocalDate): Long =
        date.atTime(LocalTime(0, 0))
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
}
