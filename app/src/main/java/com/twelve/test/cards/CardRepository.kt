package com.twelve.test.cards

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.Trace
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicReferenceArray

class CardRepository(val style: CardStyle, seed: Long) : AutoCloseable {
    private val factory = CardLayoutFactory(style, seed)
    private val cache = AtomicReferenceArray<CardLayout>(CARD_COUNT)
    private val main = Handler(Looper.getMainLooper())
    private val completedInitial = AtomicInteger()
    private var initialStart = 0
    private var initialCallback: (() -> Unit)? = null
    private var lastAnchor = -1
    private var lastDirection = 0
    @Volatile private var closed = false
    private val requestedWindow = AtomicReference<PreparationWindow?>()
    private val working = AtomicBoolean()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "CardTextPreparation")
    }

    private val prepareWindow = Runnable {
        var consumed: PreparationWindow? = null
        try {
            while (!closed && !Thread.currentThread().isInterrupted) {
                val window = requestedWindow.get() ?: break
                consumed = window
                val position = nextPosition(window)
                if (position < 0) break
                publish(position, factory.build(position))
            }
        } finally {
            working.set(false)
            // 覆盖请求恰好发生在工作线程退出时的竞态，避免丢失新的预取窗口。
            if (!closed && requestedWindow.get() !== consumed) schedulePreparation()
        }
    }

    val preparedCount = AtomicInteger()
    var hits = 0L
        private set
    var misses = 0L
        private set

    fun warmInitial(anchor: Int = 0, onReady: () -> Unit) {
        initialStart = anchor.coerceIn(0, CARD_COUNT - INITIAL_COUNT)
        initialCallback = onReady
        requestAround(initialStart, 1)
    }

    fun obtain(position: Int): CardLayout {
        cache.get(position)?.let { hits++; return it }
        misses++
        // 首次冷绑定仍然显示完整卡片；记录真实代价，避免用空白占位掩盖性能问题。
        Trace.beginSection("Card.coldBind")
        try {
            return publish(position, factory.build(position))
        } finally {
            Trace.endSection()
        }
    }

    fun requestAround(anchor: Int, direction: Int) {
        if (closed) return
        if (direction == lastDirection && lastAnchor >= 0 && kotlin.math.abs(anchor - lastAnchor) < PREFETCH_STEP) return
        lastAnchor = anchor
        lastDirection = direction
        // 主线程只发布一个窗口，不再遍历队列、删除旧任务或为每张卡片创建 Runnable。
        requestedWindow.set(PreparationWindow(anchor.coerceIn(0, CARD_COUNT - 1), direction))
        schedulePreparation()
    }

    private fun schedulePreparation() {
        if (!closed && working.compareAndSet(false, true)) {
            // 关闭和调度可能分别来自主线程与工作线程，使用锁保证不会向已关闭的线程池提交。
            synchronized(executor) {
                if (closed) working.set(false) else executor.execute(prepareWindow)
            }
        }
    }

    private fun nextPosition(window: PreparationWindow): Int {
        for (distance in 0..LOOK_AHEAD) {
            val position = window.anchor + distance * window.direction
            if (position in 0 until CARD_COUNT && cache.get(position) == null) return position
        }
        for (distance in 1..LOOK_BEHIND) {
            val position = window.anchor - distance * window.direction
            if (position in 0 until CARD_COUNT && cache.get(position) == null) return position
        }
        return -1
    }

    private fun publish(position: Int, candidate: CardLayout): CardLayout {
        if (cache.compareAndSet(position, null, candidate)) {
            preparedCount.incrementAndGet()
            if (position in initialStart until initialStart + INITIAL_COUNT && completedInitial.incrementAndGet() == INITIAL_COUNT) {
                main.post {
                    if (!closed) {
                        initialCallback?.invoke()
                        initialCallback = null
                    }
                }
            }
            return candidate
        }
        return requireNotNull(cache.get(position))
    }

    fun resetCounters() {
        hits = 0
        misses = 0
    }

    override fun close() {
        synchronized(executor) {
            closed = true
            executor.shutdownNow()
        }
        initialCallback = null
        main.removeCallbacksAndMessages(null)
    }

    private class PreparationWindow(val anchor: Int, val direction: Int)

    private companion object {
        const val INITIAL_COUNT = 24
        const val PREFETCH_STEP = 8
        const val LOOK_AHEAD = 128
        const val LOOK_BEHIND = 32
    }
}
