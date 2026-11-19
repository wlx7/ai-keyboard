package app.aikb.ime.engine

/**
 * Curated offline contextual Emoji associator (`输入后联想表情`):
 * surfaces 1-2 spot-on Emojis right alongside next-word predictions when the user finishes
 * typing expressive phrases (e.g. `生日快乐` -> `🎂`, `🎉`; `谢谢` -> `🙏`; `哈哈` -> `😂`).
 */
object EmojiPredictor {

    private val ZH_TRIGGERS: Map<String, List<String>> = mapOf(
        "生日快乐" to listOf("🎂", "🎉"),
        "生日" to listOf("🎂", "🎁"),
        "新年快乐" to listOf("🧧", "🎆"),
        "春节" to listOf("🧧", "🧨"),
        "恭喜" to listOf("🎉", "🥳"),
        "庆祝" to listOf("🎉", "🥂"),
        "太棒了" to listOf("🎉", "👍"),
        "厉害" to listOf("👍", "👏"),
        "牛" to listOf("🐮", "👍"),
        "赞" to listOf("👍"),
        "哈哈" to listOf("😂", "🤣"),
        "哈哈哈" to listOf("🤣", "😂"),
        "笑死" to listOf("🤣"),
        "开心" to listOf("😄", "✨"),
        "高兴" to listOf("😊"),
        "谢谢" to listOf("🙏", "🌹"),
        "感谢" to listOf("🙏", "❤️"),
        "辛苦了" to listOf("🙏", "☕"),
        "麻烦" to listOf("🙏"),
        "抱歉" to listOf("🙏", "😅"),
        "对不起" to listOf("🙇", "🙏"),
        "不好意思" to listOf("😅", "🙏"),
        "加油" to listOf("💪", "🔥"),
        "努力" to listOf("💪"),
        "冲" to listOf("🚀", "🔥"),
        "晚安" to listOf("🌙", "💤"),
        "睡觉" to listOf("😴", "💤"),
        "好困" to listOf("🥱", "💤"),
        "早上好" to listOf("☀️", "🌅"),
        "早安" to listOf("☀️"),
        "爱你" to listOf("❤️", "😘"),
        "喜欢" to listOf("❤️", "🥰"),
        "想你" to listOf("🥺", "❤️"),
        "难过" to listOf("😢", "🫂"),
        "伤心" to listOf("💔", "😢"),
        "哭了" to listOf("😭"),
        "生气" to listOf("😤"),
        "无语" to listOf("😅", "😑"),
        "好的" to listOf("👌", "👍"),
        "收到" to listOf("👌", "✅"),
        "没问题" to listOf("👌", "✅"),
        "可以" to listOf("👌"),
        "再见" to listOf("👋"),
        "拜拜" to listOf("👋"),
        "干杯" to listOf("🍻"),
        "啤酒" to listOf("🍺"),
        "咖啡" to listOf("☕"),
        "奶茶" to listOf("🧋"),
        "吃饭" to listOf("🍚", "😋"),
        "好吃" to listOf("😋", "👍"),
        "红包" to listOf("🧧"),
        "发财" to listOf("🧧", "💰"),
        "放假" to listOf("🥳", "🎉"),
        "周末愉快" to listOf("🥳", "☀️"),
        "一路顺风" to listOf("✈️", "🍀"),
        "一帆风顺" to listOf("⛵", "🍀"),
        "万事如意" to listOf("🧧", "✨"),
    )

    private val EN_TRIGGERS: Map<String, List<String>> = mapOf(
        "birthday" to listOf("🎂", "🎉"),
        "congrats" to listOf("🎉", "🥳"),
        "congratulations" to listOf("🎉", "🥳"),
        "thanks" to listOf("🙏", "😊"),
        "thank" to listOf("🙏"),
        "lol" to listOf("😂", "🤣"),
        "haha" to listOf("😄", "😂"),
        "love" to listOf("❤️", "🥰"),
        "goodnight" to listOf("🌙", "💤"),
        "night" to listOf("🌙"),
        "morning" to listOf("☀️", "☕"),
        "coffee" to listOf("☕"),
        "cheers" to listOf("🍻"),
        "awesome" to listOf("🔥", "👍"),
        "great" to listOf("👍", "✨"),
        "ok" to listOf("👌", "👍"),
        "okay" to listOf("👌"),
        "bye" to listOf("👋"),
        "sorry" to listOf("🙏", "😅"),
        "fire" to listOf("🔥"),
    )

    /** Matches trailing Chinese suffix (longest 4..1 chars) to contextual Emojis. */
    fun predictForChinese(beforeCursor: String): List<String> {
        if (beforeCursor.isEmpty()) return emptyList()
        val tail = beforeCursor.takeLast(6)
        for (len in minOf(4, tail.length) downTo 1) {
            val sub = tail.substring(tail.length - len)
            ZH_TRIGGERS[sub]?.let { return it }
        }
        return emptyList()
    }

    /** Matches preceding English word to contextual Emojis. */
    fun predictForEnglish(prevWord: String?): List<String> {
        if (prevWord.isNullOrBlank()) return emptyList()
        return EN_TRIGGERS[prevWord.trim().lowercase()].orEmpty()
    }
}
