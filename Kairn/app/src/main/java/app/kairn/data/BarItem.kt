package app.kairn.data

/**
 * What the reader's bottom bar can show, in the order the reader arranged.
 *
 * The first [MAIN_SLOTS] of the arrangement are drawn in the bar itself - any item, tool or mode - and the rest
 * wait behind "More". [isMode] says whether the item is a live toggle (read aloud, auto scroll) or a door to a
 * sheet; it no longer decides where the item sits.
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
