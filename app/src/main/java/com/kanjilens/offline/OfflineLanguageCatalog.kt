package com.kanjilens.offline

enum class OcrScript {
    LATIN,
    CHINESE,
    DEVANAGARI,
    JAPANESE,
    KOREAN,
}

data class OfflineLanguage(
    val tag: String,
    val displayName: String,
    val script: OcrScript? = null,
)

object OfflineLanguageCatalog {
    const val AUTO = "auto"

    private val targetNames = linkedMapOf(
        "af" to "Afrikaans",
        "sq" to "Albanian",
        "ar" to "Arabic",
        "be" to "Belarusian",
        "bn" to "Bengali",
        "bg" to "Bulgarian",
        "ca" to "Catalan",
        "zh" to "Chinese",
        "hr" to "Croatian",
        "cs" to "Czech",
        "da" to "Danish",
        "nl" to "Dutch",
        "en" to "English",
        "eo" to "Esperanto",
        "et" to "Estonian",
        "fi" to "Finnish",
        "fr" to "French",
        "gl" to "Galician",
        "ka" to "Georgian",
        "de" to "German",
        "el" to "Greek",
        "gu" to "Gujarati",
        "ht" to "Haitian Creole",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "hu" to "Hungarian",
        "is" to "Icelandic",
        "id" to "Indonesian",
        "ga" to "Irish",
        "it" to "Italian",
        "ja" to "Japanese",
        "kn" to "Kannada",
        "ko" to "Korean",
        "lv" to "Latvian",
        "lt" to "Lithuanian",
        "mk" to "Macedonian",
        "ms" to "Malay",
        "mt" to "Maltese",
        "mr" to "Marathi",
        "no" to "Norwegian",
        "fa" to "Persian",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ro" to "Romanian",
        "ru" to "Russian",
        "sk" to "Slovak",
        "sl" to "Slovenian",
        "es" to "Spanish",
        "sw" to "Swahili",
        "sv" to "Swedish",
        "tl" to "Tagalog",
        "ta" to "Tamil",
        "te" to "Telugu",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "ur" to "Urdu",
        "vi" to "Vietnamese",
        "cy" to "Welsh",
    )

    private val sourceScripts = buildMap {
        listOf(
            "af", "sq", "ca", "hr", "cs", "cy", "da", "nl", "en", "eo", "es", "et",
            "fi", "fr", "ga", "gl", "de", "ht", "hu", "id", "is", "it", "lv", "lt",
            "ms", "mt", "no", "pl", "pt", "ro", "sk", "sl", "sv", "sw", "tl", "tr", "vi",
        ).forEach { put(it, OcrScript.LATIN) }
        put("zh", OcrScript.CHINESE)
        put("hi", OcrScript.DEVANAGARI)
        put("mr", OcrScript.DEVANAGARI)
        put("ja", OcrScript.JAPANESE)
        put("ko", OcrScript.KOREAN)
    }

    val targets: List<OfflineLanguage> = targetNames
        .map { (tag, name) -> OfflineLanguage(tag, name) }
        .sortedBy { it.displayName }

    val sourceChoices: List<OfflineLanguage> = listOf(
        OfflineLanguage(AUTO, "Auto"),
    ) + targetNames
        .mapNotNull { (tag, name) -> sourceScripts[tag]?.let { OfflineLanguage(tag, name, it) } }
        .sortedBy { it.displayName }

    private val targetsByTag = targets.associateBy(OfflineLanguage::tag)
    private val sourcesByTag = sourceChoices.associateBy(OfflineLanguage::tag)

    fun source(tag: String): OfflineLanguage? = sourcesByTag[tag]

    fun requireSource(tag: String): OfflineLanguage =
        requireNotNull(source(tag)) { "Unsupported offline source language: $tag" }

    fun target(tag: String): OfflineLanguage? = targetsByTag[tag]

    fun displayName(tag: String): String =
        source(tag)?.displayName ?: target(tag)?.displayName ?: targetNames.getValue("en")
}
