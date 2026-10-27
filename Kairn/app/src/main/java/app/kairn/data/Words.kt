package app.kairn.data

/**
 * Words in raw XHTML, without parsing it: one pass over the bytes, counting runs of non-whitespace outside
 * <...>. Text nodes carry the words, so attributes and tags are skipped. Pure, so the JVM tests cover it.
 */
object Words {

    fun count(bytes: ByteArray): Int {
        var words = 0
        var inTag = false
        var inWord = false
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (inTag) {
                if (c == '>'.code) inTag = false
                inWord = false
                continue
            }
            if (c == '<'.code) { inTag = true; inWord = false; continue }
            val space = c == ' '.code || c == '\t'.code || c == '\n'.code || c == '\r'.code || c == 0x0C
            if (space) inWord = false else if (!inWord) { inWord = true; words++ }
        }
        return words
    }
}
