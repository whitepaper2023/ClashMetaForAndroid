// FEATURE-DOMAINNODE BEGIN (whole file): row adapter for the
// domain/keyword designated-node page. Row tap opens the edit dialog
// (pattern, type, target); the Delete button removes the row. The
// stale flag is display-only: it marks a rule whose target no longer
// exists (see DomainNodeActivity), it never hides the row silently.
package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterDomainNodeBinding
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

data class DomainRow(
    val type: String,
    val pattern: String,
    val target: String,
    val stale: Boolean,
)

class DomainNodeAdapter(
    private val context: Context,
    private val onPick: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
) : RecyclerView.Adapter<DomainNodeAdapter.Holder>() {
    class Holder(val binding: AdapterDomainNodeBinding) : RecyclerView.ViewHolder(binding.root)

    // Rows in insertion order; positions are the activity's entry
    // indices (the design never reorders, it only filters by search).
    var rows: List<DomainRow> = emptyList()
    // Filtered positions into `rows`, so a tap/delete always maps back
    // to the true entry index even while a search query is active.
    var visible: List<Int> = emptyList()
    var typeLabels: Map<String, String> = emptyMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterDomainNodeBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val index = visible[position]
        val row = rows[index]
        holder.binding.pattern = row.pattern
        holder.binding.detail = "${typeLabels[row.type] ?: row.type} → ${row.target}"
        holder.binding.stale = row.stale
        holder.binding.root.setOnClickListener {
            onPick(index)
        }
        holder.binding.deleteView.setOnClickListener {
            onDelete(index)
        }
    }

    override fun getItemCount(): Int {
        return visible.size
    }
}
// FEATURE-DOMAINNODE END
