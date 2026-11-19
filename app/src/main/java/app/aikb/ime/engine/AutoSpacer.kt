package app.aikb.ime.engine

/**
 * Pure offline Pangu auto-spacing helper (`盘古之白`):
 *   - `spaceCnEn`: inserts a space between Chinese Hanzi and English letters (`你好 Hello`, `AI 大模型`)
 *   - `spaceCnNum`: inserts a space between Chinese Hanzi and ASCII digits (`你好 2024`, `第 1 名`)
 *
 * Deliberately leaves `Digit <-> AsciiLetter` untouched (`5G`, `iPhone16`, `3D`) and never inserts
 * spaces next to CJK punctuation (`，。？！“”（）`).
 */
object AutoSpacer {

    fun isHan(cp: Int): Boolean =
        cp in 0x3400..0x9FFF || cp in 0xF900..0xFAFF || cp in 0x20000..0x323AF

    fun isAsciiLetter(cp: Int): Boolean =
        cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code

    fun isDigit(cp: Int): Boolean =
        cp in '0'.code..'9'.code

    fun needsSpaceBetween(leftCp: Int, rightCp: Int, spaceCnEn: Boolean, spaceCnNum: Boolean): Boolean {
        if (spaceCnEn) {
            if ((isHan(leftCp) && isAsciiLetter(rightCp)) || (isAsciiLetter(leftCp) && isHan(rightCp))) {
                return true
            }
        }
        if (spaceCnNum) {
            if ((isHan(leftCp) && isDigit(rightCp)) || (isDigit(leftCp) && isHan(rightCp))) {
                return true
            }
        }
        return false
    }

    private fun lastCodePoint(s: CharSequence?): Int {
        if (s.isNullOrEmpty()) return -1
        return Character.codePointBefore(s, s.length)
    }

    /**
     * Returns true if a leading space should be inserted before starting a new composing word
     * whose first character is [firstChar] given [beforeCursor].
     */
    fun needsLeadingSpace(
        beforeCursor: CharSequence?,
        firstChar: Char,
        spaceCnEn: Boolean,
        spaceCnNum: Boolean,
    ): Boolean {
        val prevCp = lastCodePoint(beforeCursor)
        if (prevCp < 0) return false
        return needsSpaceBetween(prevCp, firstChar.code, spaceCnEn, spaceCnNum)
    }

    /**
     * Formats [text] to be committed after [beforeCursor], inserting spaces both at the leading
     * boundary (`beforeCursor` -> `text`) and at any internal `Han <-> En/Num` transitions.
     */
    fun formatCommit(
        beforeCursor: CharSequence?,
        text: String,
        spaceCnEn: Boolean,
        spaceCnNum: Boolean,
    ): String {
        if ((!spaceCnEn && !spaceCnNum) || text.isEmpty()) return text
        val firstCp = text.codePointAt(0)
        val prevBoundaryCp = lastCodePoint(beforeCursor)
        val sb = StringBuilder(text.length + 4)
        if (prevBoundaryCp >= 0 && needsSpaceBetween(prevBoundaryCp, firstCp, spaceCnEn, spaceCnNum)) {
            sb.append(' ')
        }

        var prevCp = -1
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (prevCp >= 0 && needsSpaceBetween(prevCp, cp, spaceCnEn, spaceCnNum)) {
                sb.append(' ')
            }
            sb.appendCodePoint(cp)
            prevCp = cp
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}
