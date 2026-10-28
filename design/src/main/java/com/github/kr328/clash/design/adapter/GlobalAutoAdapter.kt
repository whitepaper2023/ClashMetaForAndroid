// FEATURE-4B BEGIN (whole file): row adapter for the manual global-auto
// subscription picker. Row tap toggles the checkbox (unlike AppNodeAdapter,
// where a tap opens a picker dialog): the checkbox itself is not
// clickable, the row click drives the toggle.
package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterGlobalAutoBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class GlobalAutoAdapter(
    private val context: Context,
    private val onToggle: (String) -> Unit,
) : RecyclerView.Adapter<GlobalAutoAdapter.Holder>() {
    class Holder(val binding: AdapterGlobalAutoBinding) : RecyclerView.ViewHolder(binding.root)

    var providers: List<String> = emptyList()
    var selected: Set<String> = emptySet()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterGlobalAutoBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = providers[position]

        holder.binding.providerName = current
        holder.binding.selected = current in selected
        holder.binding.root.setOnClickListener {
            onToggle(current)
        }
    }

    override fun getItemCount(): Int {
        return providers.size
    }
}
// FEATURE-4B END
