package com.twelve.test

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.FrameMetrics
import android.view.View
import android.view.ViewGroup
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
    private lateinit var adapter: CardAdapter
    private lateinit var panel: TextView
    private lateinit var autoButton: Button
    private val main = Handler(Looper.getMainLooper())
    private val frameCosts = LongArray(1_200)
    private var frameCount = 0
    private var frameIndex = 0
    private var overBudget = 0
    private var frameBudgetNs = 8_333_333L
    private var repository: CardRepository? = null
    private var generation = 0L
    private var autoScroll = false
    private var lastDirection = 1
    private var lastPosition = 0

    private val frameMetricsListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        if (duration > 0L) {
            frameCosts[frameIndex] = duration
            frameIndex = (frameIndex + 1) % frameCosts.size
            frameCount++
            if (duration > frameBudgetNs) overBudget++
        }
    }

    private val autoFrame = object : Choreographer.FrameCallback {
        private var direction = 1
        override fun doFrame(frameTimeNanos: Long) {
            if (!autoScroll || !::list.isInitialized) return
            val amount = max(dp(64), list.height / 3)
            list.scrollBy(0, amount * direction)
            val position = (list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
            if (position <= 0 && direction < 0) direction = 1
            if (position >= CARD_COUNT - 5 && direction > 0) direction = -1
            if (position != lastPosition) {
                lastDirection = if (position > lastPosition) 1 else -1
                lastPosition = position
                repository?.requestAround(position, lastDirection)
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val statusUpdater = object : Runnable {
        override fun run() {
            updateStatus()
            main.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(246, 248, 251)
        window.navigationBarColor = Color.rgb(246, 248, 251)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        frameBudgetNs = (1_000_000_000.0 / max(1f, display?.refreshRate ?: 60f).toDouble()).toLong()
        setContentView(createContent())
        window.addOnFrameMetricsAvailableListener(frameMetricsListener, main)
        main.post(statusUpdater)
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
            setHasFixedSize(false)
            setItemViewCacheSize(8)
            setRecycledViewPool(RecyclerView.RecycledViewPool().apply { setMaxRecycledViews(0, 16) })
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = true
            addItemDecoration(CardSpacing(dp(8)))
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val first = (layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return
                    if (first != lastPosition) {
                        lastDirection = if (first > lastPosition) 1 else -1
                        lastPosition = first
                        repository?.requestAround(first, lastDirection)
                    }
                }
            })
        }
        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        list.post {
            if (!isFinishing && !isDestroyed) regenerate()
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

    private fun regenerate() {
        if (!::list.isInitialized || list.width <= 0) return
        stopAutoScroll()
        repository?.close()
        generation++
        Trace.beginSection("Card.createDataset")
        val itemWidth = list.width - list.paddingLeft - list.paddingRight - dp(16)
        val nextRepository = CardRepository(CardStyle(this, itemWidth), System.nanoTime() xor generation)
        val nextAdapter = CardAdapter(nextRepository)
        repository = nextRepository
        adapter = nextAdapter
        list.adapter = nextAdapter
        nextRepository.warmInitial {
            if (!isFinishing && repository === nextRepository) {
                panel.text = "已排版 ${nextRepository.preparedCount.get()}/$CARD_COUNT · 当前设备刷新率 ${display?.refreshRate?.toInt() ?: 60} Hz"
                updateStatus()
            }
        }
        Trace.endSection()
        updateStatus()
    }

    private fun startAutoScroll() {
        if (autoScroll) return
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
        val fps = if (p95 > 0L) (1_000_000_000L / p95).coerceAtMost(999L) else 0L
        val hitRatio = repository?.let {
            val total = it.hits + it.misses
            if (total == 0L) 100 else (it.hits * 100 / total).toInt()
        } ?: 0
        panel.text = "目标 ${display?.refreshRate?.toInt() ?: 60} Hz / ${"%.1f".format(1_000_000.0 / frameBudgetNs)} ms · 采样 $count 帧 · p95 ${p95 / 1_000_000} ms · p99 ${p99 / 1_000_000} ms · 按 p95 估算 ${fps} FPS\n超预算帧 $overBudget · 缓存命中 $hitRatio% · 已生成 ${repository?.preparedCount?.get() ?: 0}/$CARD_COUNT"
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统字体缩放、字体、方向或屏幕尺寸变化后，重建整批 StaticLayout。
        list.post { regenerate() }
    }

    override fun onPause() {
        stopAutoScroll()
        super.onPause()
    }

    override fun onDestroy() {
        stopAutoScroll()
        main.removeCallbacks(statusUpdater)
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
