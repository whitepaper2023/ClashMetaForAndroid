// FEATURE-DOMAINNODE BEGIN (whole file): page design for
// domain/keyword designated nodes. One row per rule (pattern, type,
// target, stale marker); tapping a row edits it, Delete removes it,
// the Add button adds one. A search field above the list filters rows
// by pattern or target (case-insensitive, substring), mirroring
// AppNodeDesign's search behaviour.
package com.github.kr328.clash.design

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import com.github.kr328.clash.design.adapter.DomainNodeAdapter
import com.github.kr328.clash.design.adapter.DomainRow
import com.github.kr328.clash.design.databinding.DesignDomainNodeBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DomainNodeDesign(
    context: Context,
) : Design<DomainNodeDesign.Request>(context) {
    sealed class Request {
        data object Add : Request()
        data class Pick(val index: Int) : Request()
        data class Delete(val index: Int) : Request()
    }

    private val binding = DesignDomainNodeBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = DomainNodeAdapter(
        context,
        onPick = { index -> requests.trySend(Request.Pick(index)) },
        onDelete = { index -> requests.trySend(Request.Delete(index)) },
    )

    private var allRows: List<DomainRow> = emptyList()
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

        adapter.typeLabels = mapOf(
            "DOMAIN" to context.getString(R.string.domain_node_type_exact),
            "DOMAIN-SUFFIX" to context.getString(R.string.domain_node_type_suffix),
            "DOMAIN-KEYWORD" to context.getString(R.string.domain_node_type_keyword),
        )

        binding.domainNodeAddButton.setOnClickListener {
            requests.trySend(Request.Add)
        }

        // The search bar + add button live inside the activity bar, so
        // they grow the bar by their measured height; push the list down
        // by exactly that growth, otherwise the first rows hide under it
        // (same trick as AppNodeDesign's search bar).
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

        binding.domainNodeSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                applyFilter()
            }
        })
    }

    private fun applyFilter() {
        val visible = if (query.isBlank()) {
            allRows.indices.toList()
        } else {
            allRows.indices.filter {
                allRows[it].pattern.contains(query, ignoreCase = true) ||
                    allRows[it].target.contains(query, ignoreCase = true)
            }
        }
        adapter.rows = allRows
        adapter.visible = visible
        adapter.notifyDataSetChanged()
    }

    suspend fun patchEntries(rows: List<DomainRow>) {
        withContext(Dispatchers.Main) {
            allRows = rows
            applyFilter()
        }
    }
}
// FEATURE-DOMAINNODE END
