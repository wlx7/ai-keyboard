package app.aikb.ime.engine

import kotlin.math.abs

/**
 * Pure-memory, 90-second auto-expiring SMS / Notification / Clipboard verification code (OTP)
 * extractor & store. Never touches disk and requires zero network access.
 */
object OtpStore {

    data class OtpItem(
        val code: String,
        val source: String,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    private const val TTL_MS = 90_000L

    private val KEYWORD_REGEX = Regex(
        "(验证码|校验码|动态码|确认码|安全码|认证码|登录码|短信码|提取码|verification|verify|passcode|security\\s*code|auth\\s*code|login\\s*code|one-time|\\botp\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val DIGIT_REGEX = Regex("(?<!\\d)(\\d{4,8})(?!\\d)")
    private val BRACKET_TAG = Regex("[【\\[]([\\u4e00-\\u9fa5A-Za-z0-9]{2,12})[】\\]]")
    private val TAIL_FALSE_SUFFIX = Regex("^\\s*(年|月|日|号|元|块|角|分|秒|分钟|小时|天|周|岁|%|折)|^[.:/-]\\d")
    private val HEAD_FALSE_PREFIX = Regex("(尾号|末四位|后四位|卡号|账号|订单|客服|热线|电话|手机|金额|￥|¥|\\$)\\s*$|\\d[.:/-]$")

    @Volatile private var latest: OtpItem? = null
    @Volatile private var dismissedCode: String? = null
    private val listeners = ArrayList<() -> Unit>()

    @Synchronized fun addListener(l: () -> Unit) {
        if (l !in listeners) listeners += l
    }

    @Synchronized fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    private fun notifyChanged() {
        val snapshot = synchronized(this) { listeners.toList() }
        snapshot.forEach { it() }
    }

    fun onCandidateText(title: String?, body: String?, appLabel: String? = null, nowMs: Long = System.currentTimeMillis()) {
        val extracted = extractOtp(title, body, appLabel, nowMs) ?: return
        if (extracted.code == dismissedCode && latest != null && nowMs - latest!!.timestampMs < TTL_MS) return
        dismissedCode = null
        latest = extracted
        notifyChanged()
    }

    fun activeOtp(nowMs: Long = System.currentTimeMillis()): OtpItem? {
        val item = latest ?: return null
        if (item.code == dismissedCode || nowMs - item.timestampMs > TTL_MS) {
            latest = null
            return null
        }
        return item
    }

    fun consumeOrDismiss() {
        latest?.let { dismissedCode = it.code }
        latest = null
        notifyChanged()
    }

    /**
     * Extracts a 4..8 digit verification code from [title] + [body], avoiding bank card tail digits
     * (`尾号8899`), dates (`2024年`), durations (`5分钟`), and currency amounts.
     */
    fun extractOtp(
        title: String?,
        body: String?,
        appLabel: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): OtpItem? {
        val combined = buildString {
            if (!title.isNullOrBlank()) append(title).append(' ')
            if (!body.isNullOrBlank()) append(body)
        }.trim()
        if (combined.length < 6 || combined.length > 500) return null

        val kwMatches = KEYWORD_REGEX.findAll(combined).toList()
        if (kwMatches.isEmpty()) return null

        var bestCode: String? = null
        var bestScore = Int.MIN_VALUE

        for (m in DIGIT_REGEX.findAll(combined)) {
            val code = m.groupValues[1]
            val start = m.range.first
            val endExcl = m.range.last + 1
            val before = combined.substring(maxOf(0, start - 10), start)
            val after = combined.substring(endExcl, minOf(combined.length, endExcl + 6))

            if (TAIL_FALSE_SUFFIX.containsMatchIn(after)) continue
            if (HEAD_FALSE_PREFIX.containsMatchIn(before)) continue

            val minDist = kwMatches.minOf { kw ->
                when {
                    start >= kw.range.last -> start - kw.range.last
                    endExcl <= kw.range.first -> kw.range.first - endExcl
                    else -> 0
                }
            }

            var score = 200 - minDist * 3
            // 6-digit codes are the most standard OTP length
            if (code.length == 6) score += 35 else if (code.length == 4) score += 20
            // Strong bonus when code immediately follows "验证码[:：是为 ]"
            val immediateAfterKw = kwMatches.any { kw -> start >= kw.range.last && start - kw.range.last <= 8 }
            if (immediateAfterKw) score += 60

            if (score > bestScore) {
                bestScore = score
                bestCode = code
            }
        }

        val finalCode = bestCode ?: return null
        val bracketSource = BRACKET_TAG.find(combined)?.groupValues?.getOrNull(1)
        val cleanSource = when {
            !bracketSource.isNullOrBlank() -> bracketSource
            !appLabel.isNullOrBlank() -> appLabel
            !title.isNullOrBlank() && title.length <= 12 -> title.trim()
            else -> "信息"
        }
        return OtpItem(finalCode, cleanSource, nowMs)
    }
}
