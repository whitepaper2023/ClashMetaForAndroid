// FEATURE-5 BEGIN (whole file): page design for the per-profile node
// filter picker. Shows one row per node of the profile (display name +
// checkbox); tapping a row sends Request.Toggle and the activity flips
// the selection. The row text is the display string ("node @ group" for
// group members, bare name for proxies-section nodes); the toggle value
// is the bare node name, carried by the adapter in parallel.
package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.adapter.NodeFilterAdapter
import com.github.kr328.clash.design.databinding.DesignNodeFilterBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class NodeFilterDesign(
    context: Context,
) : Design<NodeFilterDesign.Request>(context) {
    sealed class Request {
        data class Toggle(val nodeName: String) : Request()
    }

    private val binding = DesignNodeFilterBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = NodeFilterAdapter(context) { nodeName ->
        requests.trySend(Request.Toggle(nodeName))
    }

    override val root: View
        get() = binding.root

    init {
        binding.self = this

        binding.activityBarLayout.applyFrom(context)

        binding.mainList.recyclerList.also {
            it.bindAppBarElevation(binding.activityBarLayout)
            it.applyLinearAdapter(context, adapter)
        }
    }

    suspend fun patchNodes(
        values: List<String>,
        displays: List<String>,
        selected: Set<String>,
    ) {
        withContext(Dispatchers.Main) {
            adapter.values = values
            adapter.displays = displays
            adapter.selected = selected
            adapter.notifyDataSetChanged()
        }
    }
}
// FEATURE-5 END
