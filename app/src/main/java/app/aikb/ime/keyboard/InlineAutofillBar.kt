package app.aikb.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi

/**
 * Gboard-style top Inline Autofill strip for Android 11+ (`API 30+`).
 *
 * Hosts secure `InlineContentView` chips returned by 1Password, Bitwarden, KeePassDX,
 * Google Password Manager, and SMS OTP autofill services — 100% offline IPC with zero
 * network permissions.
 */
@SuppressLint("ViewConstructor")
class InlineAutofillBar(context: Context) : FrameLayout(context) {

    private val dp = resources.displayMetrics.density
    private val scrollView: HorizontalScrollView
    private val scrollRow: LinearLayout
    private val pinnedBox: LinearLayout

    var theme: KbTheme = KbTheme.of(context)
        set(v) {
            field = v
            setBackgroundColor(v.background)
        }

    init {
        setBackgroundColor(theme.background)
        scrollRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * dp).toInt(), (3 * dp).toInt(), (6 * dp).toInt(), (3 * dp).toInt())
        }
        scrollView = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(scrollRow, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        pinnedBox = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((2 * dp).toInt(), (3 * dp).toInt(), (6 * dp).toInt(), (3 * dp).toInt())
        }

        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(scrollView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(pinnedBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        addView(outer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (44 * dp).toInt()))
    }

    fun clearChips() {
        scrollRow.removeAllViews()
        pinnedBox.removeAllViews()
        visibility = View.GONE
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun bindResponse(response: InlineSuggestionsResponse, onUpdated: (Boolean) -> Unit) {
        val suggestions = response.inlineSuggestions
        scrollRow.removeAllViews()
        pinnedBox.removeAllViews()
        if (suggestions.isEmpty()) {
            visibility = View.GONE
            onUpdated(false)
            return
        }

        val maxH = (38 * dp).toInt()
        var remaining = suggestions.size
        var addedCount = 0

        for (suggestion in suggestions) {
            val isPinned = suggestion.info.isPinned
            val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, maxH)
            try {
                suggestion.inflate(context, size, context.mainExecutor) { contentView ->
                    if (contentView != null) {
                        runCatching { contentView.isZOrderedOnTop = true }
                        val lp = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            marginStart = (3 * dp).toInt()
                            marginEnd = (3 * dp).toInt()
                        }
                        if (isPinned) pinnedBox.addView(contentView, lp) else scrollRow.addView(contentView, lp)
                        addedCount++
                    }
                    remaining--
                    if (remaining <= 0) {
                        val hasAny = addedCount > 0
                        visibility = if (hasAny) View.VISIBLE else View.GONE
                        onUpdated(hasAny)
                    }
                }
            } catch (_: Throwable) {
                remaining--
                if (remaining <= 0) {
                    val hasAny = addedCount > 0
                    visibility = if (hasAny) View.VISIBLE else View.GONE
                    onUpdated(hasAny)
                }
            }
        }
    }

    companion object {
        @RequiresApi(Build.VERSION_CODES.R)
        fun createRequest(context: Context): InlineSuggestionsRequest? = runCatching {
            val dp = context.resources.displayMetrics.density
            val minSize = Size((40 * dp).toInt(), (32 * dp).toInt())
            val maxSize = Size((280 * dp).toInt(), (38 * dp).toInt())

            val stylesBuilder = UiVersions.newStylesBuilder()
            val style = InlineSuggestionUi.newStyleBuilder().build()
            stylesBuilder.addStyle(style)
            val stylesBundle = stylesBuilder.build()

            val specs = ArrayList<InlinePresentationSpec>(6)
            repeat(6) {
                specs += InlinePresentationSpec.Builder(minSize, maxSize)
                    .setStyle(stylesBundle)
                    .build()
            }
            InlineSuggestionsRequest.Builder(specs)
                .setMaxSuggestionCount(6)
                .build()
        }.getOrNull()
    }
}
