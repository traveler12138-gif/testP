package com.twelve.test

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.Trace
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.FrameMetrics
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.twelve.test.cards.CARD_COUNT
import com.twelve.test.cards.CardAdapter
import com.twelve.test.cards.CardRepository
import com.twelve.test.cards.CardStyle
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var list: RecyclerView
    private lateinit var panel: TextView
    private lateinit var autoButton: Button
    private val main = Handler(Looper.getMainLooper())
    private val frameCosts = LongArray(1_200)
    private var frameCount = 0
    private var frameIndex = 0
    private var overBudget = 0
    private var frameBudgetNs = 8_333_333L
    private var repository: CardRepository? = null
    private var datasetSeed = System.nanoTime()
    private var expandedItems = BooleanArray(CARD_COUNT)
    private var pendingListState: Parcelable? = null
    private var pendingAnchor = 0
    private var autoScroll = false
    private var lastDirection = 1
    private var lastPosition = 0
    private val rebuildForWidth = Runnable { if (!isFinishing && !isDestroyed) regenerate(false) }

    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (duration > 0L) {
            frameCosts[frameIndex] = duration
            frameIndex = (frameIndex + 1) % frameCosts.size
            frameCount++
            val deadline = if (android.os.Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else 0L
            if (duration > (if (deadline > 0L) deadline else frameBudgetNs)) overBudget++
        }
    }

    private val autoFrame = object : Choreographer.FrameCallback {
        private var direction = 1
        override fun doFrame(frameTimeNanos: Long) {
            if (!autoScroll || !::list.isInitialized) return
            val amount = max(dp(64), list.height / 3)
            list.scrollBy(0, amount * direction)
            if (!list.canScrollVertically(direction)) direction = -direction
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val statusUpdater = object : Runnable {
        override fun run() {
            // 滚动时不刷新文字面板，避免统计界面触发整页 requestLayout 干扰被测列表。
            if (!autoScroll && (list.adapter?.itemCount ?: 0) > 0 && list.scrollState == RecyclerView.SCROLL_STATE_IDLE) updateStatus()
            main.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            datasetSeed = savedInstanceState.getLong("datasetSeed", datasetSeed)
            expandedItems = savedInstanceState.getBooleanArray("expandedItems")
                ?.takeIf { it.size == CARD_COUNT } ?: expandedItems
            pendingListState = if (android.os.Build.VERSION.SDK_INT >= 33) {
                savedInstanceState.getParcelable("listState", Parcelable::class.java)
            } else {
                @Suppress("DEPRECATION")
                savedInstanceState.getParcelable("listState")
            }
            pendingAnchor = savedInstanceState.getInt("anchor", 0)
        }
        window.statusBarColor = Color.rgb(246, 248, 251)
        window.navigationBarColor = Color.rgb(246, 248, 251)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        frameBudgetNs = (1_000_000_000.0 / max(1f, display?.refreshRate ?: 60f).toDouble()).toLong()
        setContentView(createContent())
        window.addOnFrameMetricsAvailableListener(frameMetricsListener, main)
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 248, 251))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        val title = TextView(this).apply {
            text = "RecyclerView · 1,000 张随机卡片"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.rgb(26, 36, 54))
            setPadding(dp(16), dp(13), dp(16), dp(7))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        panel = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.rgb(69, 81, 101))
            setPadding(dp(16), dp(4), dp(16), dp(10))
            text = getString(R.string.prepare_cards)
            setLines(3)
            ellipsize = TextUtils.TruncateAt.END
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        autoButton = addButton(controls, getString(R.string.auto_scroll)) {
            if (autoScroll) stopAutoScroll() else startAutoScroll()
        }
        addButton(controls, getString(R.string.regenerate)) { regenerate() }
        addButton(controls, getString(R.string.reset_metrics)) {
            frameCount = 0
            overBudget = 0
            frameIndex = 0
            repository?.resetCounters()
            updateStatus()
        }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            itemAnimator = null
            // RecyclerView 本身由父布局给定尺寸，条目可变高度不影响这个条件。
            setHasFixedSize(true)
            setItemViewCacheSize(8)
            setRecycledViewPool(RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 16) })
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
            addItemDecoration(CardSpacing(dp(8)))
            addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft && repository != null) {
                    removeCallbacks(rebuildForWidth)
                    post(rebuildForWidth)
                }
            }
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val first = (layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
                    if (first != lastPosition) {
                        lastDirection = if (first > lastPosition) 1 else -1
                        lastPosition = first
                        repository?.requestAround(first, lastDirection)
                    }
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) stopAutoScroll()
                    if (newState == RecyclerView.SCROLL_STATE_IDLE && !autoScroll) updateStatus()
                }
            })
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.post {
            if (!isFinishing && !isDestroyed) regenerate(false)
        }
        return root
    }

    private fun addButton(parent: LinearLayout, label: String, action: () -> Unit): Button {
        val button = Button(this).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minHeight = dp(44)
            minimumHeight = dp(44)
            setPadding(dp(8), 0, dp(8), 0)
            isAllCaps = false
            setOnClickListener { action() }
        }
        parent.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        return button
    }

    private fun regenerate(newData: Boolean = true) {
        if (!::list.isInitialized || list.width <= 0) return
        list.removeCallbacks(rebuildForWidth)
        val manager = list.layoutManager as LinearLayoutManager
        if (newData) {
            datasetSeed = System.nanoTime()
            expandedItems.fill(false)
            pendingListState = null
            pendingAnchor = 0
        } else if ((list.adapter?.itemCount ?: 0) > 0) {
            pendingListState = manager.onSaveInstanceState()
            pendingAnchor = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        }
        stopAutoScroll()
        list.stopScroll()
        repository?.close()
        Trace.beginSection("Card.createDataset")
        // 字体和宽度变化后旧 Holder 的 TextView 参数不再适用，先回收，再清空共享池。
        list.adapter = null
        list.recycledViewPool.clear()
        autoButton.isEnabled = false
        panel.text = getString(R.string.prepare_cards)
        lastPosition = pendingAnchor
        lastDirection = 1
        val itemWidth = max(1, list.width - list.paddingLeft - list.paddingRight - dp(8) * 2)
        val nextRepository = CardRepository(CardStyle(this, itemWidth), datasetSeed)
        val nextAdapter = CardAdapter(nextRepository, expandedItems)
        repository = nextRepository
        // 先挂载空 Adapter 再预热，避免 setAdapter 清空已经预热的 RecycledViewPool。
        list.adapter = nextAdapter
        var textReady = false
        var viewsReady = false
        fun showWhenReady() {
            if (!isFinishing && !isDestroyed && repository === nextRepository && textReady && viewsReady) {
                nextAdapter.showItems()
                val scrollState = pendingListState
                if (scrollState != null) manager.onRestoreInstanceState(scrollState) else manager.scrollToPositionWithOffset(0, 0)
                autoButton.isEnabled = true
                updateStatus()
            }
        }
        nextRepository.warmInitial(pendingAnchor) {
            textReady = true
            showWhenReady()
        }
        fun prepareHolder(remaining: Int) {
            if (isFinishing || isDestroyed || repository !== nextRepository) return
            if (remaining == 0) {
                viewsReady = true
                showWhenReady()
            } else {
                // TextView 只在主线程创建；首屏显示前每帧预热一个 Holder，不在 fling 时批量创建。
                list.recycledViewPool.putRecycledView(nextAdapter.createViewHolder(list, 0))
                list.postOnAnimation { prepareHolder(remaining - 1) }
            }
        }
        list.postOnAnimation { prepareHolder(12) }
        Trace.endSection()
    }

    private fun startAutoScroll() {
        if (autoScroll) return
        if ((list.adapter?.itemCount ?: 0) == 0) return
        list.stopScroll()
        autoScroll = true
        lastPosition = (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
        autoButton.text = getString(R.string.stop_scroll)
        Choreographer.getInstance().postFrameCallback(autoFrame)
    }

    private fun stopAutoScroll() {
        if (autoScroll) Choreographer.getInstance().removeFrameCallback(autoFrame)
        autoScroll = false
        if (::autoButton.isInitialized) autoButton.text = getString(R.string.auto_scroll)
    }

    private fun updateStatus() {
        if (!::panel.isInitialized) return
        val count = minOf(frameCount, frameCosts.size)
        val values = LongArray(count)
        for (i in 0 until count) values[i] = frameCosts[i]
        values.sort()
        val p95 = values.getOrNull(if (count == 0) 0 else ((count - 1) * 0.95).toInt()) ?: 0L
        val p99 = values.getOrNull(if (count == 0) 0 else ((count - 1) * 0.99).toInt()) ?: 0L
        val hitRatio = repository?.let {
            val total = it.hits + it.misses
            if (total == 0L) 100 else (it.hits * 100 / total).toInt()
        } ?: 0
        frameBudgetNs = (1_000_000_000.0 / max(1f, display?.refreshRate ?: 60f).toDouble()).toLong()
        // 帧处理耗时的倒数并不代表实际呈现 FPS，仅展示 FrameMetrics 直接测量的指标。
        panel.text = "TextView · ${display?.refreshRate?.toInt() ?: 60} Hz · 预算 ${"%.2f".format(frameBudgetNs / 1_000_000.0)} ms\n最近 $count 帧 · p95 ${"%.2f".format(p95 / 1_000_000.0)} ms · p99 ${"%.2f".format(p99 / 1_000_000.0)} ms\n累计超预算 $overBudget · 命中 $hitRatio% · 冷绑定 ${repository?.misses ?: 0} · 已准备 ${repository?.preparedCount?.get() ?: 0}/$CARD_COUNT"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统字体缩放、字体、方向或屏幕尺寸变化后，重建预计算文字和 TextView 参数。
        list.removeCallbacks(rebuildForWidth)
        list.post(rebuildForWidth)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val manager = list.layoutManager as LinearLayoutManager
        val ready = (list.adapter?.itemCount ?: 0) > 0
        outState.putLong("datasetSeed", datasetSeed)
        outState.putBooleanArray("expandedItems", expandedItems.copyOf())
        outState.putParcelable("listState", if (ready) manager.onSaveInstanceState() else pendingListState)
        outState.putInt("anchor", if (ready) manager.findFirstVisibleItemPosition().coerceAtLeast(0) else pendingAnchor)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        stopAutoScroll()
        main.removeCallbacks(statusUpdater)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(statusUpdater)
        main.post(statusUpdater)
    }

    override fun onDestroy() {
        stopAutoScroll()
        main.removeCallbacks(statusUpdater)
        list.removeCallbacks(rebuildForWidth)
        window.removeOnFrameMetricsAvailableListener(frameMetricsListener)
        repository?.close()
        super.onDestroy()
    }

    private class CardSpacing(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: android.graphics.Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            outRect.set(spacing, if (position == 0) spacing else spacing / 2, spacing, spacing / 2)
        }
    }
}
