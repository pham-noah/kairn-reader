package app.kairn.data

import androidx.compose.runtime.Immutable
import org.json.JSONObject

/**
 * A book's cast, as the reader arranged it: which voice slot each name gets, and whether the cast is on at
 * all. One file per book beside the positions (`data/cast/<id>.json`), because it is a property of this
 * reader's taste, not of the EPUB - and it belongs in a backup with the rest of them.
 *
 * A SLOT, not a voice name: the phone's engine is not the same engine next month, and a stored
 * "en-us-x-iom-local" would silently fall back to nothing after an update. Slot 0 is the narrator; slots 1-4
 * are the four voices the engine is asked for, in the order the reader sees them.
 */
@Immutable
data class BookCast(
    val enabled: Boolean = true,
    /** speaker name -> slot (1..[SLOTS]); a name absent here is given its own slot on first sight. */
    val voices: Map<String, Int> = emptyMap(),
) {
    fun slotOf(speaker: String): Int = voices[speaker]?.coerceIn(1, SLOTS) ?: 0

    fun withVoice(speaker: String, slot: Int): BookCast =
        copy(voices = voices + (speaker to slot.coerceIn(1, SLOTS)))

    fun toJson() = JSONObject().put("enabled", enabled).put(
        "voices",
        JSONObject().apply { voices.forEach { (k, v) -> put(k, v) } },
    )

    companion object {
        /** The narrator plus four character voices: beyond that a phone's own engine has nothing left. */
        const val SLOTS = 4

        fun from(o: JSONObject?): BookCast {
            if (o == null) return BookCast()
            val voices = o.optJSONObject("voices")?.let { v ->
                v.keys().asSequence().mapNotNull { k ->
                    val slot = v.optInt(k, 0)
                    if (slot in 1..SLOTS) k to slot else null
                }.take(SLOTS * 4).toMap()
            } ?: emptyMap()
            return BookCast(enabled = o.optBoolean("enabled", true), voices = voices)
        }
    }
}
