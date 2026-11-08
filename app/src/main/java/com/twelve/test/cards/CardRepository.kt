package com.twelve.test.cards

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.Trace
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

class CardRepository(style: CardStyle, seed: Long) : AutoCloseable {
    private val factory = CardLayoutFactory(style, seed)
    private val cache = AtomicReferenceArray<CardLayout>(CARD_COUNT)
    private val jobs = arrayOfNulls<PrepareJob>(CARD_COUNT)
    private val main = Handler(Looper.getMainLooper())
    private val completedInitial = AtomicInteger()
    private var initialCallback: (() -> Unit)? = null
    private var batch = 0L
    private var lastAnchor = -1
    private var lastDirection = 0
    @Volatile private var closed = false
    private val executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, PriorityBlockingQueue(),
        { runnable ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                runnable.run()
            }, "CardTextPreparation")
        },
    )

    val preparedCount = AtomicInteger()
    var hits = 0L
        private set
    var misses = 0L
        private set

    fun warmInitial(onReady: () -> Unit) {
        initialCallback = onReady
        requestAround(0, 1)
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
        val generation = ++batch
        for (distance in 0..128) {
            enqueue(anchor + distance * direction, generation, distance)
        }
        for (distance in 1..32) {
            enqueue(anchor - distance * direction, generation, 128 + distance)
        }
    }

    private fun enqueue(position: Int, generation: Long, distance: Int) {
        if (position !in 0 until CARD_COUNT || cache.get(position) != null) return
        val old = jobs[position]
        if (old != null && !executor.queue.remove(old)) return
        val job = PrepareJob(position, generation, distance)
        jobs[position] = job
        executor.execute(job)
    }

    private fun publish(position: Int, candidate: CardLayout): CardLayout {
        if (cache.compareAndSet(position, null, candidate)) {
            preparedCount.incrementAndGet()
            if (position < INITIAL_COUNT && completedInitial.incrementAndGet() == INITIAL_COUNT) {
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
        closed = true
        initialCallback = null
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
    }

    private inner class PrepareJob(
        private val position: Int,
        private val generation: Long,
        private val distance: Int,
    ) : Runnable, Comparable<PrepareJob> {
        override fun compareTo(other: PrepareJob): Int {
            val order = other.generation.compareTo(generation)
            return if (order != 0) order else distance.compareTo(other.distance)
        }

        override fun run() {
            if (!closed && cache.get(position) == null) {
                publish(position, factory.build(position))
            }
        }
    }

    private companion object {
        const val INITIAL_COUNT = 24
        const val PREFETCH_STEP = 8
    }
}
