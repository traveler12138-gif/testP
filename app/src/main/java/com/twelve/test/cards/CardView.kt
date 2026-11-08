package com.twelve.test.cards

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.TextView
import com.twelve.test.R

class CardView(context: Context, private val style: CardStyle) : ViewGroup(context) {
    private var card: CardLayout? = null
    private var expanded = false
    private var animatedHeight = 0
    private var animator: ValueAnimator? = null
    private var onExpansionChanged: ((Boolean) -> Unit)? = null
    private val content = TextGrid(context, style)
    private val contentClip = Rect()
    private val dividerPaint = Paint().apply { color = Color.rgb(232, 236, 242) }
    private val expandLabel = context.getString(R.string.expand_card)
    private val collapseLabel = context.getString(R.string.collapse_card)
    private val footer = Button(context).apply {
        isAllCaps = false
        style.buttonSpec.applyTo(this)
        gravity = Gravity.CENTER
        background = RippleDrawable(
            ColorStateList.valueOf(Color.rgb(219, 232, 255)),
            null,
            ColorDrawable(Color.WHITE),
        )
        setOnClickListener { changeExpansion(!expanded) }
    }

    init {
        setWillNotDraw(false)
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = style.radius
        }
        clipToOutline = true
        isSaveEnabled = false
        // 容器不合并朗读，保留每一个 key/value TextView 的原生无障碍节点。
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, style.buttonHeight))
    }

    fun bind(layout: CardLayout, isExpanded: Boolean, listener: (Boolean) -> Unit) {
        cancelAnimation()
        card = layout
        expanded = isExpanded && layout.expandable
        onExpansionChanged = listener
        animatedHeight = targetHeight()
        content.bind(layout, expanded)
        footer.visibility = if (layout.expandable) VISIBLE else GONE
        updateFooter()
        requestLayout()
        invalidate()
    }

    fun unbind() {
        cancelAnimation()
        card = null
        onExpansionChanged = null
        content.unbind()
        // 回收时保留 TextView 及其文字布局，避免清空再赋值导致额外分配。
    }

    fun finishAnimation() {
        if (animator == null) return
        cancelAnimation()
        if (card != null) {
            animatedHeight = targetHeight()
            content.showRows(expanded)
            requestLayout()
            invalidate()
        }
    }

    private fun cancelAnimation() {
        // 先移除监听器，防止 cancel 的结束回调改写下一个条目的状态。
        animator?.run {
            removeAllUpdateListeners()
            removeAllListeners()
            cancel()
        }
        animator = null
    }

    private fun targetHeight(): Int {
        val current = card ?: return 0
        return if (expanded) current.expandedHeight else current.collapsedHeight
    }

    private fun updateFooter() {
        val label = if (expanded) collapseLabel else expandLabel
        if (footer.text != label) footer.text = label
        footer.stateDescription = context.getString(if (expanded) R.string.expanded_state else R.string.collapsed_state)
    }

    private fun changeExpansion(value: Boolean) {
        if (expanded == value || card?.expandable != true) return
        cancelAnimation()
        expanded = value
        onExpansionChanged?.invoke(value)
        // 动画开始前一次性补齐隐藏行；动画过程中只改变裁剪矩形和外层高度。
        content.showRows(true)
        updateFooter()
        val target = targetHeight()
        if (ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow) {
            animator = ValueAnimator.ofInt(animatedHeight, target).apply {
                duration = 220
                interpolator = ANIMATION_INTERPOLATOR
                addUpdateListener {
                    val next = it.animatedValue as Int
                    if (animatedHeight != next) {
                        animatedHeight = next
                        requestLayout()
                    }
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        animator = null
                        content.showRows(expanded)
                    }
                })
                start()
            }
        } else {
            animatedHeight = target
            content.showRows(expanded)
            requestLayout()
        }
        footer.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val current = card
        val measuredWidth = resolveSize(style.width, widthMeasureSpec)
        if (current != null) {
            // 内容始终按完整尺寸测量。动画没有修改文字和宽度时，跳过全部子 TextView 测量。
            if (content.isLayoutRequested || content.measuredWidth != measuredWidth || content.measuredHeight != current.fullContentHeight) {
                content.measure(exact(measuredWidth), exact(current.fullContentHeight))
            }
            if (current.expandable && (footer.isLayoutRequested || footer.measuredWidth != measuredWidth)) {
                footer.measure(exact(measuredWidth), exact(style.buttonHeight))
            }
        }
        setMeasuredDimension(measuredWidth, resolveSize(animatedHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val current = card ?: return
        val contentBottom = if (current.expandable) height - style.buttonHeight else height
        // 内容尺寸不随外层高度改变，原生 TextView 的布局与 RenderNode 可直接复用。
        content.layout(0, 0, width, current.fullContentHeight)
        val clippedBottom = contentBottom.coerceAtLeast(0)
        if (contentClip.right != width || contentClip.bottom != clippedBottom) {
            contentClip.set(0, 0, width, clippedBottom)
            content.clipBounds = contentClip
            invalidate()
        }
        if (current.expandable) footer.layout(0, contentBottom, width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (card?.expandable == true) {
            val y = (height - style.buttonHeight).toFloat()
            canvas.drawRect(style.padding.toFloat(), y, (width - style.padding).toFloat(), y + 1f, dividerPaint)
        }
    }

    override fun onDetachedFromWindow() {
        finishAnimation()
        super.onDetachedFromWindow()
    }

    private class RowViews(val key: TextView, val value: TextView) {
        var bound: RowLayout? = null

        fun bind(row: RowLayout) {
            if (bound === row) return
            // 尺寸由 TextGrid 统一提交；固定高度使 setText 不必按 WRAP_CONTENT 向上试探重排。
            key.layoutParams.height = row.keyHeight
            value.layoutParams.height = row.valueHeight
            key.setText(row.key, TextView.BufferType.NORMAL)
            value.setText(row.value, TextView.BufferType.NORMAL)
            bound = row
        }
    }

    private class TextGrid(context: Context, private val style: CardStyle) : ViewGroup(context) {
        private var card: CardLayout? = null
        private var visibleRows = 0
        // 每个 Holder 只创建一次最多 20 个 TextView，没有逐行 LinearLayout 或权重测量。
        private val rows = Array(MAX_CARD_ROWS) {
            val key = style.keySpec.createView(context)
            val value = style.valueSpec.createView(context)
            key.id = View.generateViewId()
            value.id = View.generateViewId()
            key.labelFor = value.id
            key.visibility = GONE
            value.visibility = GONE
            addView(key, LayoutParams(style.keyWidth, LayoutParams.WRAP_CONTENT))
            addView(value, LayoutParams(style.valueWidth, LayoutParams.WRAP_CONTENT))
            RowViews(key, value)
        }

        init {
            isSaveEnabled = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun bind(layout: CardLayout, expanded: Boolean) {
            card = layout
            showRows(expanded)
            requestLayout()
        }

        fun unbind() {
            card = null
        }

        fun showRows(all: Boolean) {
            val current = card ?: return
            val count = if (all) current.rows.size else current.collapsedRowCount
            for (index in rows.indices) {
                val holder = rows[index]
                if (index < count) {
                    holder.bind(current.rows[index])
                    holder.key.visibility = VISIBLE
                    holder.value.visibility = VISIBLE
                } else {
                    holder.key.visibility = GONE
                    holder.value.visibility = GONE
                }
            }
            visibleRows = count
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val current = card
            if (current != null) {
                for (index in 0 until visibleRows) {
                    val row = current.rows[index]
                    val views = rows[index]
                    // 精确尺寸单次测量，TextView 可复用预计算的字形；原生控件仍负责最终换行和省略。
                    views.key.measure(exact(style.keyWidth), exact(row.keyHeight))
                    views.value.measure(exact(style.valueWidth), exact(row.valueHeight))
                }
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val current = card ?: return
            for (index in 0 until visibleRows) {
                val row = current.rows[index]
                val views = rows[index]
                views.key.layout(style.keyX, row.top, style.keyX + style.keyWidth, row.top + row.keyHeight)
                views.value.layout(style.valueX, row.top, style.valueX + style.valueWidth, row.top + row.valueHeight)
            }
        }
    }

    private companion object {
        val ANIMATION_INTERPOLATOR = AccelerateDecelerateInterpolator()
        fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
    }
}
