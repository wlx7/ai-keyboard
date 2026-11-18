package app.aikb.ime.keyboard

enum class KeyType {
    CHAR,       // commits [Key.text] (letters go through the engine)
    TEXT,       // commits a literal string such as ".com"
    SHIFT, DELETE, ENTER, SPACE,
    TO_SYMBOLS, TO_SYMBOLS2, TO_LETTERS,
    LANG, EMOJI, SPACER,
}

class Key(
    val type: KeyType,
    val label: String = "",
    val text: String = label,
    val width: Float = 1f,
    val alts: List<String> = emptyList(),
) {
    val isFunctional: Boolean
        get() = when (type) {
            KeyType.CHAR, KeyType.TEXT, KeyType.SPACE, KeyType.SPACER -> false
            else -> true
        }
    val isLetter: Boolean get() = type == KeyType.CHAR && label.length == 1 && label[0] in 'a'..'z'
}

class Row(val keys: List<Key>)

class Layout(val id: String, val rows: List<Row>, val units: Float = 10f)

/** Which page of keys is showing. */
enum class Page { LETTERS, SYMBOLS, SYMBOLS2, NUMBER, PHONE }

/** Bottom-row flavour chosen from the input field (see Scene). */
enum class BottomRow { TEXT, EMAIL, URL }

object Layouts {
    private val accents = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "c" to listOf("ç", "ć", "č"),
        "e" to listOf("è", "é", "ê", "ë", "ē", "ę", "ė"),
        "i" to listOf("î", "ï", "í", "ī", "į", "ì"),
        "l" to listOf("ł"),
        "n" to listOf("ñ", "ń"),
        "o" to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
        "s" to listOf("ß", "ś", "š"),
        "u" to listOf("û", "ü", "ù", "ú", "ū"),
        "y" to listOf("ÿ"),
        "z" to listOf("ž", "ź", "ż"),
    )

    private fun letterRow(chars: String, digits: Boolean): List<Key> = chars.mapIndexed { i, c ->
        val s = c.toString()
        val alt = mutableListOf<String>()
        if (digits) alt += ((i + 1) % 10).toString()
        alt += accents[s].orEmpty()
        Key(KeyType.CHAR, s, s, 1f, alt)
    }

    private fun chars(s: String, width: Float = 1f, alts: Map<String, List<String>> = emptyMap()): List<Key> =
        s.split(' ').filter { it.isNotEmpty() }.map { Key(KeyType.CHAR, it, it, width, alts[it].orEmpty()) }

    private val spacer = Key(KeyType.SPACER, width = 0.1f)

    private fun bottom(kind: BottomRow, chinese: Boolean, page: Page, spaceLabel: String? = null): Row {
        val modeKey = if (page == Page.LETTERS) Key(KeyType.TO_SYMBOLS, "123", width = 1.28f)
        else Key(KeyType.TO_LETTERS, if (chinese) (spaceLabel ?: "拼音") else "ABC", width = 1.28f)
        val emoji = Key(KeyType.EMOJI, "emoji", width = 1.12f)
        val enter = Key(KeyType.ENTER, "", width = if (kind == BottomRow.TEXT) 1.75f else 2.0f)
        if (kind == BottomRow.TEXT && chinese && page == Page.LETTERS) {
            // Chinese letters page: quick ，and 。 beside the space bar (long-press for ？！ etc.).
            return Row(listOf(
                Key(KeyType.TO_SYMBOLS, "123", width = 1.15f),
                Key(KeyType.EMOJI, "emoji", width = 1.05f),
                Key(KeyType.CHAR, "，", "，", 0.9f, listOf("、", "；", "：", ",")),
                Key(KeyType.SPACE, spaceLabel ?: "空格", " ", 3.15f),
                Key(KeyType.CHAR, "。", "。", 0.9f, listOf("？", "！", "…", ".")),
                Key(KeyType.LANG, "中/英", width = 1.2f),
                Key(KeyType.ENTER, "", width = 1.65f),
            ))
        }
        return when (kind) {
            BottomRow.TEXT -> Row(listOf(
                modeKey, emoji,
                Key(KeyType.SPACE, if (chinese) "空格" else "space", " ", 4.55f),
                Key(KeyType.LANG, "中/英", width = 1.3f),
                enter,
            ))
            BottomRow.EMAIL -> Row(listOf(
                modeKey, emoji,
                Key(KeyType.SPACE, "space", " ", 3.1f),
                Key(KeyType.CHAR, "@", "@", 1.25f),
                Key(KeyType.CHAR, ".", ".", 1.25f, listOf("-", "_")),
                enter,
            ))
            BottomRow.URL -> Row(listOf(
                modeKey, emoji,
                Key(KeyType.CHAR, ".", ".", 1.55f, listOf("-", "_", ":")),
                Key(KeyType.CHAR, "/", "/", 1.55f, listOf("?", "=", "&", "#")),
                Key(KeyType.TEXT, ".com", ".com", 2.0f, listOf(".cn", ".net", ".org", ".com.cn", ".io")),
                Key(KeyType.ENTER, "", width = 2.5f),
            ))
        }
    }

    fun letters(kind: BottomRow, chinese: Boolean, spaceLabel: String? = null): Layout = Layout("letters", listOf(
        Row(letterRow("qwertyuiop", digits = true)),
        Row(listOf(Key(KeyType.SPACER, width = 0.5f)) + letterRow("asdfghjkl", false) + Key(KeyType.SPACER, width = 0.5f)),
        Row(listOf(Key(KeyType.SHIFT, width = 1.4f), spacer) + letterRow("zxcvbnm", false) +
            listOf(spacer, Key(KeyType.DELETE, width = 1.4f))),
        bottom(kind, chinese, Page.LETTERS, spaceLabel),
    ))

    private val symAltsEn = mapOf(
        "-" to listOf("–", "—", "•"), "$" to listOf("€", "£", "¥", "₩", "₽", "¢"),
        "&" to listOf("§"), "\"" to listOf("“", "”", "„", "«", "»"), "." to listOf("…"),
        "?" to listOf("¿"), "!" to listOf("¡"), "'" to listOf("‘", "’", "`"),
        "0" to listOf("°"), "/" to listOf("\\"),
    )
    private val symAltsZh = mapOf(
        "-" to listOf("–", "—", "•"), "¥" to listOf("$", "€", "£", "₩"),
        "“" to listOf("\"", "「", "『"), "”" to listOf("\"", "」", "』"),
        "。" to listOf("…", "."), "，" to listOf(","), "？" to listOf("?"), "！" to listOf("!"),
        "、" to listOf("\\"), "（" to listOf("("), "）" to listOf(")"), "0" to listOf("°"),
    )

    fun symbols(kind: BottomRow, chinese: Boolean): Layout {
        val alts = if (chinese) symAltsZh else symAltsEn
        val row2 = if (chinese) "- / ： ； （ ） ¥ @ “ ”" else "- / : ; ( ) $ & @ \""
        val row3 = if (chinese) "。 ， 、 ？ ！" else ". , ? ! '"
        return Layout("symbols", listOf(
            Row(chars("1 2 3 4 5 6 7 8 9 0", alts = alts)),
            Row(chars(row2, alts = alts)),
            Row(listOf(Key(KeyType.TO_SYMBOLS2, "#+=", width = 1.4f), spacer) + chars(row3, 1.4f, alts) +
                listOf(spacer, Key(KeyType.DELETE, width = 1.4f))),
            bottom(kind, chinese, Page.SYMBOLS),
        ))
    }

    fun symbols2(kind: BottomRow, chinese: Boolean): Layout {
        val row1 = if (chinese) "【 】 { } # % ^ * + =" else "[ ] { } # % ^ * + ="
        val row2 = if (chinese) "_ — \\ | ~ 《 》 $ & ·" else "_ \\ | ~ < > € £ ¥ •"
        val row3 = if (chinese) "… ～ 「 」 ‘" else ". , ? ! '"
        return Layout("symbols2", listOf(
            Row(chars(row1)),
            Row(chars(row2)),
            Row(listOf(Key(KeyType.TO_SYMBOLS, "123", width = 1.4f), spacer) + chars(row3, 1.4f) +
                listOf(spacer, Key(KeyType.DELETE, width = 1.4f))),
            bottom(kind, chinese, Page.SYMBOLS2),
        ))
    }

    fun numberPad(decimal: Boolean, signed: Boolean): Layout {
        val w = 2.5f
        fun c(s: String, alts: List<String> = emptyList()) = Key(KeyType.CHAR, s, s, w, alts)
        return Layout("number", listOf(
            Row(listOf(c("1"), c("2"), c("3"), c(if (signed) "-" else "+", listOf("+", "-")))),
            Row(listOf(c("4"), c("5"), c("6"), c(if (decimal) "." else ",", listOf(".", ",")))),
            Row(listOf(c("7"), c("8"), c("9"), Key(KeyType.DELETE, width = w))),
            Row(listOf(Key(KeyType.TO_LETTERS, "ABC", width = w), c("%", listOf("/", "*", "=")), c("0"),
                Key(KeyType.ENTER, "", width = w))),
        ))
    }

    fun phonePad(): Layout {
        val w = 2.5f
        fun c(s: String) = Key(KeyType.CHAR, s, s, w)
        return Layout("phone", listOf(
            Row(listOf(c("1"), c("2"), c("3"), c("+"))),
            Row(listOf(c("4"), c("5"), c("6"), Key(KeyType.SPACE, "空格", " ", w))),
            Row(listOf(c("7"), c("8"), c("9"), Key(KeyType.DELETE, width = w))),
            Row(listOf(c("*"), c("0"), c("#"), Key(KeyType.ENTER, "", width = w))),
        ))
    }
}
