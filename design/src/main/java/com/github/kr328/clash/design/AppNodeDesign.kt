// FEATURE-2 BEGIN (whole file): page design for per-app designated nodes.
// Shows one row per app (label, package, current node or 未指定); tapping a
// row sends Request.Pick and the activity shows the node picker dialog.
package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.adapter.AppNodeAdapter
import com.github.kr328.clash.design.databinding.DesignAppNodeBinding
import com.github.kr328.clash.design.model.AppInfo
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppNodeDesign(
    context: Context,
) : Design<AppNodeDesign.Request>(context) {
    sealed class Request {
        data class Pick(val packageName: String) : Request()
    }

    private val binding = DesignAppNodeBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = AppNodeAdapter(context) { packageName ->
        requests.trySend(Request.Pick(packageName))
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

        adapter.blockedNote = context.getString(R.string.app_node_blocked_first)
        adapter.unspecifiedText = context.getString(R.string.app_node_not_specified)
    }

    suspend fun patchApps(apps: List<AppInfo>, rules: Map<String, String>, blocked: Set<String>) {
        withContext(Dispatchers.Main) {
            adapter.apps = apps
            adapter.rules = rules
            adapter.blocked = blocked
            adapter.notifyDataSetChanged()
        }
    }
}
// FEATURE-2 END
