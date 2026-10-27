// FEATURE-2 BEGIN (whole file): row adapter for the per-app node page.
// Row tap opens the node picker (it does NOT toggle a checkbox like
// AppAdapter): the checkbox only reflects "this app has a node rule".
package com.github.kr328.clash.design.adapter

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.kr328.clash.design.databinding.AdapterAppNodeBinding
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root

class AppNodeAdapter(
    private val context: Context,
    private val onPick: (String) -> Unit,
) : RecyclerView.Adapter<AppNodeAdapter.Holder>() {
    class Holder(val binding: AdapterAppNodeBinding) : RecyclerView.ViewHolder(binding.root)

    var apps: List<AppInfo> = emptyList()
    var rules: Map<String, String> = emptyMap()
    var blocked: Set<String> = emptySet()
    var blockedNote: String = ""
    var unspecifiedText: String = ""

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(
            AdapterAppNodeBinding
                .inflate(context.layoutInflater, context.root, false)
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val current = apps[position]
        val node = rules[current.packageName]

        holder.binding.app = current
        holder.binding.selected = node != null
        holder.binding.node = when {
            current.packageName in blocked -> blockedNote
            node != null -> node
            else -> unspecifiedText
        }
        holder.binding.root.setOnClickListener {
            onPick(current.packageName)
        }
    }

    override fun getItemCount(): Int {
        return apps.size
    }
}
// FEATURE-2 END
