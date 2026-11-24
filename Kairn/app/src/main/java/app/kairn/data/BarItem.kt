package app.kairn.data

/**
 * What the reader's bottom bar can show, in the order the reader arranged.
 *
 * The first [MAIN_SLOTS] of the arrangement are drawn in the bar itself - any item, tool or mode - and the rest
 * wait behind "More". [isMode] says whether the item is a live toggle (read aloud, auto scroll) or a door to a
 * sheet; it does not decide where the item sits.
 *
 * The setting stores what is HIDDEN and the order the reader arranged, so a bar item added in a later
 * release is visible and last for everyone who never opened the setting - an empty hidden set and the
 * default order are exactly the bar every earlier version drew.
 */
enum class BarItem(val isMode: Boolean) {
    READ_ALOUD(isMode = true),
    AUTO_SCROLL(isMode = true),
    CONTENTS(isMode = false),
    SEARCH(isMode = false),
    NOTES(isMode = false),
    DISPLAY(isMode = false),
    ;

    companion object {
        /** How many items the bar shows before "More": the two slots read aloud and auto scroll used to keep. */
        const val MAIN_SLOTS = 2

        /** The order the bar has always drawn, used when nothing was arranged. */
        val DEFAULT_ORDER: List<BarItem> = entries.toList()
    }
}

/**
 * Three arrangements of the same six items, because nobody drags six rows to get a bar they are happy with.
 *
 * A preset only sets the order and never touches [AppState]-style hiding: what a reader hid stays hidden, and
 * nudging a single row by hand simply stops any preset being selected. The first [BarItem.MAIN_SLOTS] of each
 * list are what the bar draws, so the lists read as "what is in the bar", rest behind More.
 */
enum class BarPreset(val order: List<BarItem>) {
    /** Reading tools only: the two doors that page and search, voice and display behind More. */
    MINIMAL(listOf(BarItem.CONTENTS, BarItem.SEARCH, BarItem.NOTES, BarItem.READ_ALOUD, BarItem.DISPLAY, BarItem.AUTO_SCROLL)),

    /** What the bar has always drawn: the two live toggles on the front. */
    STANDARD(BarItem.DEFAULT_ORDER),

    /** Listening: read aloud and the type controls, which is what gets touched mid-chapter while it speaks. */
    VOICE(listOf(BarItem.READ_ALOUD, BarItem.DISPLAY, BarItem.CONTENTS, BarItem.SEARCH, BarItem.NOTES, BarItem.AUTO_SCROLL)),
    ;

    companion object {
        /** Which arrangement this is, or null when the reader has arranged the rows by hand. */
        fun of(order: List<BarItem>): BarPreset? = entries.firstOrNull { it.order == order }
    }
}
