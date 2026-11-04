package app.kairn.data

/**
 * What the reader's bottom bar can show: the two mode pills and the four tools behind "More".
 *
 * The setting stores what is HIDDEN rather than what is shown, so a bar item added in a later release is
 * visible for everyone who never opened the setting - an empty set is the old behaviour, exactly.
 */
enum class BarItem {
    READ_ALOUD,
    AUTO_SCROLL,
    CONTENTS,
    SEARCH,
    NOTES,
    DISPLAY,
}
