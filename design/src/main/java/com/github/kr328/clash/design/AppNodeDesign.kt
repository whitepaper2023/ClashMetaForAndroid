// FEATURE-2 BEGIN (whole file): page design for per-app designated nodes.
// Shows one row per app (label, package, current node or 未指定); tapping a
// row sends Request.Pick and the activity shows the node picker dialog.
// FEATURE-2C: a search field above the list filters rows by app label or
// package name (case-insensitive, substring); the filter is re-applied on
// every patchApps so picking a node never resets what the user typed.
package com.github.kr328.clash.design

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
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

    // FEATURE-2C: full (unfiltered) data; the adapter only ever sees the
    // filtered view produced by applyFilter().
    private var allApps: List<AppInfo> = emptyList()
    private var allRules: Map<String, String> = emptyMap()
    private var allBlocked: Set<String> = emptySet()
    private var query: String = ""

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

        // FEATURE-2C: the search bar lives inside the activity bar, so it
        // grows the bar by its measured height; push the list down by
        // exactly that growth, otherwise the first rows hide under it.
        // Measured at runtime (no hardcoded dp): the bar's total height
        // minus the list's original top padding is the search height.
        binding.activityBarLayout.post {
            val list = binding.mainList.recyclerList
            val growth = binding.activityBarLayout.height - list.paddingTop
            if (growth > 0) {
                list.setPadding(
                    list.paddingLeft,
                    list.paddingTop + growth,
                    list.paddingRight,
                    list.paddingBottom
                )
            }
        }

        binding.appNodeSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                applyFilter()
            }
        })
    }

    // FEATURE-2C: runs on the main thread in both call sites (patchApps
    // switches to Main; TextWatcher callbacks already run on Main).
    private fun applyFilter() {
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }
        adapter.apps = filtered
        adapter.rules = allRules
        adapter.blocked = allBlocked
        adapter.notifyDataSetChanged()
    }

    suspend fun patchApps(apps: List<AppInfo>, rules: Map<String, String>, blocked: Set<String>) {
        withContext(Dispatchers.Main) {
            allApps = apps
            allRules = rules
            allBlocked = blocked
            applyFilter()
        }
    }
}
// FEATURE-2 END
