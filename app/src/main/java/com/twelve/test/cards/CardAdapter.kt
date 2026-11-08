package com.twelve.test.cards

import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

class CardAdapter(
    private val repository: CardRepository,
    val expandedItems: BooleanArray = BooleanArray(CARD_COUNT),
) : RecyclerView.Adapter<CardAdapter.Holder>() {
    init {
        setHasStableIds(true)
        stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
    }

    override fun getItemCount() = CARD_COUNT
    override fun getItemId(position: Int) = position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = CardView(parent.context)
        view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return Holder(view, expandedItems)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(repository.obtain(position), position)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.card.unbind()
        super.onViewRecycled(holder)
    }

    override fun onViewDetachedFromWindow(holder: Holder) {
        holder.card.finishAnimation()
        super.onViewDetachedFromWindow(holder)
    }

    class Holder(val card: CardView, private val states: BooleanArray) : RecyclerView.ViewHolder(card) {
        private var boundPosition = RecyclerView.NO_POSITION
        // 每个 Holder 仅创建一次回调，快速滚动重绑时不再分配 Lambda。
        private val expansionListener: (Boolean) -> Unit = { expanded ->
            if (boundPosition != RecyclerView.NO_POSITION) states[boundPosition] = expanded
        }

        fun bind(layout: CardLayout, position: Int) {
            boundPosition = position
            card.bind(layout, states[position], expansionListener)
        }
    }
}
