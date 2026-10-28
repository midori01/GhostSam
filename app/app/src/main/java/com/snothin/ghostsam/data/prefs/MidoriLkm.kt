package com.snothin.ghostsam.data.prefs

/** MidoriSU embeds two independently built LKM families. KOWSU uses the normal KMI name;
 *  XXKSU uses the `xx-` prefix understood by `ksud late-load --kmi`. */
enum class MidoriLkm(
    val label: String,
    val kmiPrefix: String,
) {
    XX("XXKSU", "xx-"),
    KOW("KOWSU", "");

    fun ksudKmi(kmi: String): String = kmiPrefix + kmi

    companion object {
        fun parse(raw: String?): MidoriLkm =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: XX
    }
}
