package com.smg.pos

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CallsAdapter : RecyclerView.Adapter<CallsAdapter.Holder>() {
    private val items = mutableListOf<WaiterCall>()
    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale("ru"))
    private val statusCodes = listOf("new", "in_progress", "done", "cancelled")
    private var canManage = false
    private var showRestaurant = false
    private var errors: Map<Int, String> = emptyMap()
    private var pending: Set<Int> = emptySet()

    var onStatusSelected: ((callId: Int, status: String) -> Unit)? = null

    fun submit(
        calls: List<WaiterCall>,
        canManage: Boolean,
        showRestaurant: Boolean,
        errors: Map<Int, String>,
        pending: Set<Int>,
    ) {
        if (
            items == calls &&
            this.canManage == canManage &&
            this.showRestaurant == showRestaurant &&
            this.errors == errors &&
            this.pending == pending
        ) {
            return
        }
        items.clear()
        items.addAll(calls)
        this.canManage = canManage
        this.showRestaurant = showRestaurant
        this.errors = errors
        this.pending = pending
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_call, parent, false)
        return Holder(view).also { holder ->
            holder.change.setOnClickListener { openStatusDialog(holder) }
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val call = items[position]
        val table = call.tableNumber.ifBlank { "—" }
        holder.table.text = holder.itemView.context.getString(R.string.field_table, table)
        val restaurant = call.restaurantName.ifBlank { "—" }
        holder.restaurant.isVisible = showRestaurant
        holder.restaurant.text = holder.itemView.context.getString(R.string.field_restaurant, restaurant)
        holder.reason.text = holder.itemView.context.getString(R.string.field_reason, reasonLabel(call.reason))
        paintStatus(holder.status, call.status)
        val created = call.createdAt?.atZone(ZoneId.systemDefault())?.let { timeFormat.format(it) } ?: "—"
        holder.created.text = holder.itemView.context.getString(R.string.field_created, created)

        val saving = call.id in pending
        holder.change.isVisible = canManage
        holder.change.isEnabled = canManage && !saving
        holder.change.text = holder.itemView.context.getString(
            if (saving) R.string.status_saving else R.string.change_status,
        )
        val error = errors[call.id]
        holder.error.isVisible = canManage && !error.isNullOrBlank()
        holder.error.text = error.orEmpty()
    }

    override fun getItemCount(): Int = items.size

    private fun openStatusDialog(holder: Holder) {
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION) return
        val call = items[position]
        if (call.id in pending) return
        val labels = statusCodes.map { statusLabel(it) }.toTypedArray()
        MaterialAlertDialogBuilder(holder.itemView.context)
            .setTitle(R.string.change_status)
            .setItems(labels) { _, which ->
                val chosen = statusCodes[which]
                val current = items.firstOrNull { it.id == call.id }?.status ?: call.status
                if (chosen != current) onStatusSelected?.invoke(call.id, chosen)
            }
            .show()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val table: TextView = view.findViewById(R.id.call_table)
        val restaurant: TextView = view.findViewById(R.id.call_restaurant)
        val reason: TextView = view.findViewById(R.id.call_reason)
        val status: Chip = view.findViewById(R.id.call_status)
        val created: TextView = view.findViewById(R.id.call_created)
        val change: Button = view.findViewById(R.id.call_status_change)
        val error: TextView = view.findViewById(R.id.call_status_error)
    }

    private fun paintStatus(chip: Chip, status: String) {
        val (background, foreground) = when (status) {
            "new" -> R.color.status_new_bg to R.color.status_new_text
            "in_progress" -> R.color.status_progress_bg to R.color.status_progress_text
            "done" -> R.color.status_done_bg to R.color.status_done_text
            else -> R.color.status_cancelled_bg to R.color.status_cancelled_text
        }
        chip.text = statusLabel(status)
        chip.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(chip.context, background))
        chip.setTextColor(ContextCompat.getColor(chip.context, foreground))
    }
}
