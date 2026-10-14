package com.smg.pos

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class CallsAdapter : RecyclerView.Adapter<CallsAdapter.Holder>() {
    private val items = mutableListOf<WaiterCall>()
    private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale("ru"))

    fun submit(calls: List<WaiterCall>) {
        if (items == calls) return
        items.clear()
        items.addAll(calls)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_call, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val call = items[position]
        val table = call.tableNumber.ifBlank { "—" }
        holder.table.text = holder.itemView.context.getString(R.string.field_table, table)
        holder.reason.text = holder.itemView.context.getString(R.string.field_reason, reasonLabel(call.reason))
        holder.status.text = holder.itemView.context.getString(R.string.field_status, statusLabel(call.status))
        val created = call.createdAt?.atZone(ZoneId.systemDefault())?.let { timeFormat.format(it) } ?: "—"
        holder.created.text = holder.itemView.context.getString(R.string.field_created, created)
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val table: TextView = view.findViewById(R.id.call_table)
        val reason: TextView = view.findViewById(R.id.call_reason)
        val status: TextView = view.findViewById(R.id.call_status)
        val created: TextView = view.findViewById(R.id.call_created)
    }
}
