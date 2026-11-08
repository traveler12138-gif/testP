package com.twelve.test.cards

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Trace
import android.text.Layout
import android.text.PrecomputedText
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

const val CARD_COUNT = 1_000
const val MAX_CARD_ROWS = 10

data class CardRow(val key: String, val value: String)

class RowLayout(
    val key: PrecomputedText,
    val value: PrecomputedText,
    val keyHeight: Int,
    val valueHeight: Int,
    val top: Int,
    val height: Int,
)

class CardLayout(
    val id: Int,
    val rows: Array<RowLayout>,
    val fullContentHeight: Int,
    val style: CardStyle,
) {
    val expandable = fullContentHeight > style.collapsedHeight
    val collapsedHeight = if (expandable) style.collapsedHeight else fullContentHeight
    val expandedHeight = fullContentHeight + if (expandable) style.buttonHeight else 0
    val collapsedContentHeight = collapsedHeight - if (expandable) style.buttonHeight else 0
    val collapsedRowCount = rows.indexOfFirst { it.top >= collapsedContentHeight }
        .let { if (it < 0) rows.size else it }
}

class CardStyle(context: Context, val width: Int) {
    private val density = context.resources.displayMetrics.density
    val padding = dp(16)
    val rowGap = dp(9)
    val columnGap = dp(12)
    val radius = dp(12).toFloat()
    val collapsedHeight = dp(200)
    val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val keySpec = CardTextSpec(context, 14f, Color.rgb(104, 116, 136), 1, rtl)
    val valueSpec = CardTextSpec(context, 16f, Color.rgb(27, 37, 55), 2, rtl)
    val buttonSpec = CardTextSpec(context, 14f, Color.rgb(37, 92, 203), 1, rtl)
    private val buttonFont = buttonSpec.params.textPaint.fontMetricsInt
    private val buttonMinimum = buttonSpec.minimumFontMetrics
    val buttonHeight = max(
        dp(48),
        max(buttonFont.bottom - buttonFont.top, buttonMinimum?.let { (it.bottom - it.top).roundToInt() } ?: 0) + dp(16),
    )
    val keyWidth = max(1, ((width - padding * 2 - columnGap) * 0.30f).roundToInt())
    val valueWidth = max(1, width - padding * 2 - columnGap - keyWidth)
    val keyX = if (rtl) width - padding - keyWidth else padding
    val valueX = if (rtl) padding else padding + keyWidth + columnGap

    fun dp(value: Int) = (value * density).roundToInt()
}

class CardTextSpec(
    context: Context,
    sizeSp: Float,
    private val color: Int,
    val maxLines: Int,
    private val rtl: Boolean,
) {
    val params: PrecomputedText.Params
    val minimumFontMetrics: Paint.FontMetrics?
    private val useBoundsForWidth: Boolean
    private val shiftDrawingOffset: Boolean

    init {
        // 在主线程获取系统字体、字重调整和 SP 非线性缩放，工作线程只使用参数快照。
        val prototype = TextView(context)
        prototype.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        prototype.textLocales = context.resources.configuration.locales
        configure(prototype)
        params = prototype.textMetricsParams
        if (Build.VERSION.SDK_INT >= 35) {
            useBoundsForWidth = prototype.useBoundsForWidth
            shiftDrawingOffset = prototype.shiftDrawingOffsetForStartOverhang
            minimumFontMetrics = prototype.minimumFontMetrics
                ?: if (prototype.isLocalePreferredLineHeightForMinimumUsed) {
                    Paint.FontMetrics().also { prototype.paint.getFontMetricsForLocale(it) }
                } else null
        } else {
            useBoundsForWidth = false
            shiftDrawingOffset = false
            minimumFontMetrics = null
        }
    }

    fun createView(context: Context): TextView = TextView(context).also(::applyTo)

    fun applyTo(view: TextView) {
        configure(view)
        // 与后台 PrecomputedText 的参数完全一致，避免 setText 触发参数不匹配或重新塑形。
        view.textMetricsParams = params
        view.setTextColor(color)
        if (Build.VERSION.SDK_INT >= 35) {
            view.useBoundsForWidth = useBoundsForWidth
            view.shiftDrawingOffsetForStartOverhang = shiftDrawingOffset
            view.setLocalePreferredLineHeightForMinimumUsed(false)
            view.minimumFontMetrics = minimumFontMetrics
        }
    }

    fun configureLayout(builder: StaticLayout.Builder) {
        if (Build.VERSION.SDK_INT >= 33) builder.setLineBreakConfig(params.lineBreakConfig)
        if (Build.VERSION.SDK_INT >= 35) {
            // 新系统默认的语言最小行高和字形边界参与换行，必须与 TextView 一同快照。
            builder.setUseBoundsForWidth(useBoundsForWidth)
            builder.setMinimumFontMetrics(minimumFontMetrics)
        }
    }

    private fun configure(view: TextView) {
        view.setPadding(0, 0, 0, 0)
        view.minWidth = 0
        view.minHeight = 0
        view.minimumWidth = 0
        view.minimumHeight = 0
        view.maxLines = maxLines
        // 不使用 setSingleLine，保留正常宽度约束和原生 END 省略行为。
        view.setHorizontallyScrolling(false)
        view.ellipsize = TextUtils.TruncateAt.END
        view.includeFontPadding = true
        view.setFallbackLineSpacing(true)
        view.setLineSpacing(0f, 1f)
        view.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        view.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        view.textDirection = if (rtl) View.TEXT_DIRECTION_FIRST_STRONG_RTL else View.TEXT_DIRECTION_FIRST_STRONG_LTR
        view.gravity = Gravity.TOP or Gravity.START
        view.isHorizontalFadingEdgeEnabled = false
        view.isVerticalFadingEdgeEnabled = false
        view.setTextIsSelectable(false)
        view.isSaveEnabled = false
    }
}

class CardLayoutFactory(private val style: CardStyle, private val seed: Long) {
    // key 文案只有固定字段与有限序号组合，缓存其高度，减少后台重复换行测量。
    private val keyHeights = ConcurrentHashMap<String, Int>()

    fun build(position: Int): CardLayout {
        Trace.beginSection("Card.prepareText")
        try {
            val source = generate(position)
            var top = style.padding
            val rows = Array(source.size) { index ->
                val item = source[index]
                val key = PrecomputedText.create(item.key, style.keySpec.params)
                val value = PrecomputedText.create(item.value, style.valueSpec.params)
                val keyHeight = keyHeights[item.key] ?: textHeight(key, style.keySpec, style.keyWidth)
                    .also { keyHeights.putIfAbsent(item.key, it) }
                val valueHeight = textHeight(value, style.valueSpec, style.valueWidth)
                val height = max(keyHeight, valueHeight)
                RowLayout(key, value, keyHeight, valueHeight, top, height).also { top += height + style.rowGap }
            }
            return CardLayout(
                position,
                rows,
                top - style.rowGap + style.padding,
                style,
            )
        } finally {
            Trace.endSection()
        }
    }

    private fun textHeight(text: PrecomputedText, spec: CardTextSpec, width: Int): Int {
        // StaticLayout 仅用于后台计算高度，绘制全部由原生 TextView 完成，不缓存两份布局。
        return StaticLayout.Builder.obtain(text, 0, text.length, android.text.TextPaint(spec.params.textPaint), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (style.rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true)
            .setUseLineSpacingFromFallbacks(true)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setMaxLines(spec.maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(width)
            .also(spec::configureLayout)
            .build().height
    }

    private fun generate(position: Int): List<CardRow> {
        // 每个位置拥有独立随机源，缓存失效、线程执行顺序及回收重绑都不会改变条目身份。
        val random = Random(seed xor (position.toLong() * -7046029254386353131L))
        return List(5 + random.nextInt(6)) { row ->
            val key = KEYS[random.nextInt(KEYS.size)] + if (row % 3 == 0) " / ${row + 1}" else ""
            val value = when (random.nextInt(6)) {
                0 -> "${position + 1}-${row + 1}"
                1 -> "正常 · ${random.nextInt(10_000)}"
                2 -> "这是一段随机生成的说明文字，用来验证长文本换行及第二行末尾的省略效果，编号 ${random.nextInt(100_000)}。"
                3 -> "Android RecyclerView / multilingual text / ${random.nextInt(100_000)} / a-long-unbroken-value-for-ellipsis-check"
                4 -> "系统字体与显示大小设置会影响文本排版，卡片需要重新测量。${random.nextInt(1_000)}"
                else -> "混合 English、中文、العربية 以及 emoji 🚀，序号 ${random.nextInt(10_000)}"
            }
            CardRow(key, value)
        }
    }

    private companion object {
        val KEYS = arrayOf("设备名称", "状态", "创建时间", "描述信息", "这个字段名称非常长需要单行省略", "Owner", "详细备注", "位置", "编号", "系统配置")
    }
}
