package androidx.compose.ui.text.intl

import androidx.compose.runtime.Immutable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

typealias PlatformLocale = KotlinLocale

@Immutable
actual class Locale internal constructor(
    internal val platformLocale: KotlinLocale,
) {
    private val languageTag: String = platformLocale.languageTag

    actual val language: String get() = platformLocale.language
    actual val script: String get() = platformLocale.script
    actual val region: String get() = platformLocale.region

    actual fun toLanguageTag(): String = languageTag

    actual override fun equals(other: Any?): Boolean =
        other is Locale && toLanguageTag() == other.toLanguageTag()

    actual override fun hashCode(): Int = toLanguageTag().hashCode()

    actual override fun toString(): String = toLanguageTag()

    actual companion object {
        actual val current: Locale
            get() = platformLocaleDelegate.current[0]
    }

    actual constructor(languageTag: String) : this(KotlinLocale.parse(languageTag))
}

class KotlinLocale(
    val languageTag: String,
    val language: String,
    val script: String,
    val region: String,
) {
    companion object {
        fun parse(languageTag: String): KotlinLocale {
            val normalized = languageTag.replace('_', '-')
            val parts = normalized.split('-')
            val language = parts.firstOrNull()?.lowercase().orEmpty().ifEmpty { "en" }
            val script = parts.getOrNull(1)?.takeIf { it.length == 4 }?.let { capitalize(it) }.orEmpty()
            val regionIndex = if (script.isNotEmpty()) 2 else 1
            val region = parts.getOrNull(regionIndex)?.uppercase().orEmpty()
            return KotlinLocale(normalized, language, script, region)
        }

        @OptIn(ExperimentalForeignApi::class)
        fun fromEnvironment(): KotlinLocale {
            val lang = getenv("LANG")?.toKString()?.substringBefore('.')?.substringBefore('@')
            return parse(lang?.takeIf { it.isNotBlank() } ?: "en-US")
        }

        private fun capitalize(value: String): String =
            value.first().uppercaseChar() + value.drop(1).lowercase()
    }
}

internal actual fun createPlatformLocaleDelegate(): PlatformLocaleDelegate =
    object : PlatformLocaleDelegate {
        override val current: LocaleList
            get() = LocaleList(listOf(Locale(KotlinLocale.fromEnvironment())))
    }

private val rtlLanguages = setOf("ar", "fa", "he", "iw", "ji", "ur", "yi")

internal actual fun Locale.isRtl(): Boolean = language in rtlLanguages
