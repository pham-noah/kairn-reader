package app.kairn.data

/**
 * What the reader's bottom bar can show: the two mode pills and the four tools behind "More".
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
        /** The order the bar has always drawn, used when nothing was arranged. */
        val DEFAULT_ORDER: List<BarItem> = entries.toList()
    }
}
