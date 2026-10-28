// FEATURE-5 BEGIN (whole file): row adapter for the per-profile node
// filter picker. Row tap toggles the checkbox; the checkbox itself is
// not clickable, the row click drives the toggle. `values` are bare node
// names (the toggle values and the filter keys); `displays` run parallel
// and are the row texts ("node @ group" for group members).
package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterNodeFilterBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class NodeFilterAdapter(
    private val context: Context,
    private val onToggle: (String) -> Unit,
) : RecyclerView.Adapter<NodeFilterAdapter.Holder>() {
    class Holder(val binding: AdapterNodeFilterBinding) : RecyclerView.ViewHolder(binding.root)

    var values: List<String> = emptyList()
    var displays: List<String> = emptyList()
    var selected: Set<String> = emptySet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterNodeFilterBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = values[position]

        holder.binding.nodeName = displays[position]
        holder.binding.selected = current in selected
        holder.binding.root.setOnClickListener {
            onToggle(current)
        }
    }

    override fun getItemCount(): Int {
        return values.size
    }
}
// FEATURE-5 END
