// FEATURE-4B BEGIN (whole file): page design for the manual global-auto
// subscription picker. Shows one row per proxy-provider in the active
// config (name + checkbox); tapping a row sends Request.Toggle and the
// activity flips the selection.
package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import com.github.kr328.clash.design.adapter.GlobalAutoAdapter
import com.github.kr328.clash.design.databinding.DesignGlobalAutoBinding
import com.github.kr328.clash.design.util.applyFrom
import com.github.kr328.clash.design.util.applyLinearAdapter
import com.github.kr328.clash.design.util.bindAppBarElevation
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.root
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GlobalAutoDesign(
    context: Context,
) : Design<GlobalAutoDesign.Request>(context) {
    sealed class Request {
        data class Toggle(val providerName: String) : Request()
    }

    private val binding = DesignGlobalAutoBinding
        .inflate(context.layoutInflater, context.root, false)

    private val adapter = GlobalAutoAdapter(context) { providerName ->
        requests.trySend(Request.Toggle(providerName))
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

    suspend fun patchProviders(providers: List<String>, selected: Set<String>) {
        withContext(Dispatchers.Main) {
            adapter.providers = providers
            adapter.selected = selected
            adapter.notifyDataSetChanged()
        }
    }
}
// FEATURE-4B END
