package com.twelve.test.cards

import android.content.Context
import android.graphics.Color
import android.os.Trace
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import java.util.Locale
import java.util.Random
import kotlin.math.max
import kotlin.math.roundToInt

const val CARD_COUNT = 1_000

data class CardRow(val key: String, val value: String)

class RowLayout(
    val key: StaticLayout,
    val value: StaticLayout,
    val top: Int,
    val height: Int,
)

class CardLayout(
    val id: Int,
    val rows: Array<RowLayout>,
    val fullContentHeight: Int,
    val accessibilityText: String,
    val style: CardStyle,
) {
    val expandable = fullContentHeight + style.buttonHeight > style.collapsedHeight
    val collapsedHeight = if (expandable) style.collapsedHeight else fullContentHeight
    val expandedHeight = fullContentHeight + if (expandable) style.buttonHeight else 0
}

class CardStyle(context: Context, val width: Int) {
    private val resources = context.resources
    private val metrics = resources.displayMetrics
    val padding = dp(16)
    val rowGap = dp(9)
    val columnGap = dp(12)
    val radius = dp(12).toFloat()
    val collapsedHeight = dp(200)
    val buttonHeight = dp(48)
    val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val locale: Locale = resources.configuration.locales[0]
    val keyWidth = max(1, ((width - padding * 2 - columnGap) * 0.30f).roundToInt())
    val valueWidth = max(1, width - padding * 2 - columnGap - keyWidth)
    val keyX = if (rtl) width - padding - keyWidth else padding
    val valueX = if (rtl) padding else padding + keyWidth + columnGap
    val keyPaint = makePaint(context, 14f, Color.rgb(104, 116, 136))
    val valuePaint = makePaint(context, 16f, Color.rgb(27, 37, 55))
    val buttonPaint = makePaint(context, 14f, Color.rgb(37, 92, 203))

    fun dp(value: Int) = (value * metrics.density).roundToInt()

    private fun makePaint(context: Context, sizeSp: Float, color: Int): TextPaint {
        // 通过 TextView 获取系统字体、字重调整和字体回退参数，SP 换算也交给系统处理非线性缩放。
        val prototype = TextView(context)
        prototype.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        prototype.setTextColor(color)
        prototype.textLocale = locale
        return TextPaint(prototype.paint).apply { this.color = color }
    }
}

class CardLayoutFactory(private val style: CardStyle, private val seed: Long) {
    fun build(position: Int): CardLayout {
        Trace.beginSection("Card.prepareText")
        try {
            val source = generate(position)
            var top = style.padding
            val rows = Array(source.size) { index ->
                val item = source[index]
                val key = textLayout(item.key, style.keyPaint, style.keyWidth, 1)
                val value = textLayout(item.value, style.valuePaint, style.valueWidth, 2)
                val height = max(key.height, value.height)
                RowLayout(key, value, top, height).also { top += height + style.rowGap }
            }
            return CardLayout(
                position,
                rows,
                top - style.rowGap + style.padding,
                source.joinToString("\n") { "${it.key}：${it.value}" },
                style,
            )
        } finally {
            Trace.endSection()
        }
    }

    private fun textLayout(text: String, sourcePaint: TextPaint, width: Int, maxLines: Int): StaticLayout {
        // 每个 Layout 独占 TextPaint；工作线程与主线程不会并发修改同一支画笔。
        return StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(sourcePaint), width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(if (style.rtl) TextDirectionHeuristics.FIRSTSTRONG_RTL else TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setIncludePad(true)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(width)
            .build()
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
