package com.vitara.routine.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.vitara.routine.R
import com.vitara.routine.data.RoutineBlock
import com.vitara.routine.util.TimeText
import java.time.LocalDate
import java.time.LocalTime

/** The routine list: time + status, title, on/off switch and the ⋮ menu. */
class BlockAdapter(private val listener: Listener) : RecyclerView.Adapter<BlockAdapter.Holder>() {

    interface Listener {
        fun onBlockClicked(block: RoutineBlock)
        fun onBlockMenu(block: RoutineBlock, anchor: View)
        fun onBlockEnabledChanged(block: RoutineBlock, enabled: Boolean)
    }

    private val blocks = mutableListOf<RoutineBlock>()

    fun submit(items: List<RoutineBlock>) {
        blocks.clear()
        blocks.addAll(items)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_block, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(blocks[position])

    override fun getItemCount(): Int = blocks.size

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val context = view.context
        private val time: TextView = view.findViewById(R.id.blockTime)
        private val status: TextView = view.findViewById(R.id.blockStatus)
        private val title: TextView = view.findViewById(R.id.blockTitle)
        private val enabled: MaterialSwitch = view.findViewById(R.id.blockEnabled)
        private val more: ImageButton = view.findViewById(R.id.blockMore)

        fun bind(block: RoutineBlock) {
            time.text = TimeText.window(block)
            title.text = block.title

            val (label, colourRes) = statusOf(block)
            status.text = label
            status.setTextColor(ContextCompat.getColor(context, colourRes))

            enabled.setOnCheckedChangeListener(null)
            enabled.isChecked = block.enabled
            enabled.setOnCheckedChangeListener { _, isChecked ->
                listener.onBlockEnabledChanged(block, isChecked)
            }

            itemView.setOnClickListener { listener.onBlockClicked(block) }
            more.setOnClickListener { listener.onBlockMenu(block, it) }
        }

        /** DONE / NOW for today, the weekdays when it does not run today, OFF when disabled. */
        private fun statusOf(block: RoutineBlock): Pair<String, Int> {
            if (!block.enabled) return context.getString(R.string.status_off) to R.color.status_done
            val today = LocalDate.now().dayOfWeek.value
            if (today !in block.days) {
                return TimeText.days(context, block.days) to R.color.status_done
            }
            val now = LocalTime.now().let { it.hour * 60 + it.minute }
            val running = if (block.crossesMidnight) {
                now >= block.startMinute || now < block.endMinute
            } else {
                now >= block.startMinute && now < block.endMinute
            }
            return when {
                running -> context.getString(R.string.status_now) to R.color.status_now
                !block.crossesMidnight && now >= block.endMinute ->
                    context.getString(R.string.status_done) to R.color.status_done

                else -> "" to R.color.status_done
            }
        }
    }
}
