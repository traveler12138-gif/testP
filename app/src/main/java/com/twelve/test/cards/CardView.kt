package com.twelve.test.cards

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateDecelerateInterpolator
import com.twelve.test.R

class CardView(context: Context) : View(context) {
    private var card: CardLayout? = null
    private var expanded = false
    private var animatedHeight = 0
    private var animator: ValueAnimator? = null
    private var onExpansionChanged: ((Boolean) -> Unit)? = null
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dividerPaint = Paint().apply { color = Color.rgb(232, 236, 242) }
    private val pressedPaint = Paint().apply { color = Color.rgb(237, 243, 255) }
    private val clipBounds = android.graphics.Rect()
    private val expandLabel = context.getString(R.string.expand_card)
    private val collapseLabel = context.getString(R.string.collapse_card)

    init {
        setWillNotDraw(false)
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener {
            if (card?.expandable == true) changeExpansion(!expanded, true)
        }
    }

    fun bind(layout: CardLayout, isExpanded: Boolean, listener: (Boolean) -> Unit) {
        finishAnimation()
        card = layout
        expanded = isExpanded
        onExpansionChanged = listener
        animatedHeight = targetHeight()
        isClickable = layout.expandable
        isPressed = false
        requestLayout()
        invalidate()
    }

    fun unbind() {
        finishAnimation()
        card = null
        onExpansionChanged = null
    }

    fun finishAnimation() {
        animator?.cancel()
        animator = null
        if (card != null) {
            val target = targetHeight()
            if (animatedHeight != target) {
                animatedHeight = target
                requestLayout()
                invalidate()
            }
        }
    }

    private fun targetHeight(): Int {
        val current = card ?: return 0
        return if (expanded && current.expandable) current.expandedHeight else current.collapsedHeight
    }

    private fun changeExpansion(value: Boolean, animate: Boolean) {
        if (expanded == value || card?.expandable != true) return
        animator?.cancel()
        expanded = value
        onExpansionChanged?.invoke(value)
        val target = targetHeight()
        if (animate && ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow) {
            animator = ValueAnimator.ofInt(animatedHeight, target).apply {
                duration = 220
                interpolator = ANIMATION_INTERPOLATOR
                addUpdateListener {
                    val next = it.animatedValue as Int
                    if (animatedHeight != next) {
                        animatedHeight = next
                        // 仅更新高度，StaticLayout 与文本内容始终复用，禁止逐帧排版。
                        requestLayout()
                        invalidate()
                    }
                }
                start()
            }
        } else {
            animatedHeight = target
            requestLayout()
            invalidate()
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(card?.style?.width ?: suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(animatedHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val current = card ?: return
        val style = current.style
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), style.radius, style.radius, backgroundPaint)
        val contentBottom = if (current.expandable) height - style.buttonHeight else height
        val saved = canvas.save()
        canvas.clipRect(0, 0, width, contentBottom)
        // 展开的长卡片也只录制当前屏幕内的文字绘制指令，减少 RenderThread 工作。
        canvas.getClipBounds(clipBounds)
        for (row in current.rows) {
            if (row.top >= contentBottom || row.top >= clipBounds.bottom) break
            if (row.top + row.height <= clipBounds.top) continue
            var checkpoint = canvas.save()
            canvas.translate(style.keyX.toFloat(), row.top.toFloat())
            row.key.draw(canvas)
            canvas.restoreToCount(checkpoint)
            checkpoint = canvas.save()
            canvas.translate(style.valueX.toFloat(), row.top.toFloat())
            row.value.draw(canvas)
            canvas.restoreToCount(checkpoint)
        }
        canvas.restoreToCount(saved)
        if (current.expandable) {
            val footerTop = height - style.buttonHeight
            if (isPressed) {
                canvas.drawRect(style.padding.toFloat(), footerTop.toFloat(), (width - style.padding).toFloat(), height.toFloat(), pressedPaint)
            }
            canvas.drawRect(style.padding.toFloat(), footerTop.toFloat(), (width - style.padding).toFloat(), footerTop + 1f, dividerPaint)
            val label = if (expanded) collapseLabel else expandLabel
            val paint = style.buttonPaint
            val x = (width - paint.measureText(label)) * 0.5f
            val baseline = footerTop + style.buttonHeight * 0.5f - (paint.ascent() + paint.descent()) * 0.5f
            canvas.drawText(label, x, baseline, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val current = card ?: return false
        if (!current.expandable) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && event.y < height - current.style.buttonHeight) return false
        return super.onTouchEvent(event)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        if (card?.expandable == true) invalidate()
    }

    override fun onDetachedFromWindow() {
        finishAnimation()
        super.onDetachedFromWindow()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val current = card ?: return
        info.className = if (current.expandable) "android.widget.Button" else "android.widget.TextView"
        info.text = current.accessibilityText
        if (current.expandable) {
            info.stateDescription = context.getString(if (expanded) R.string.expanded_state else R.string.collapsed_state)
            info.addAction(if (expanded) AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (card?.expandable == true) {
            when (action) {
                AccessibilityNodeInfo.ACTION_EXPAND -> { changeExpansion(true, true); return true }
                AccessibilityNodeInfo.ACTION_COLLAPSE -> { changeExpansion(false, true); return true }
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private companion object {
        val ANIMATION_INTERPOLATOR = AccelerateDecelerateInterpolator()
    }
}
