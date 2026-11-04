// FEATURE-SYSTEM-MARK BEGIN (whole file): designated-node page menu.
// Before 2.11.43 this page had no menu at all, so there was no way to
// toggle system apps — that is the missing "系统应用的勾选" Lu reported.
// The checkbox means what its label says: checked = system apps are
// shown. Toggling stores the choice and asks the activity to reload.
package com.github.kr328.clash.design.component

import android.content.Context
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.github.kr328.clash.design.AppNodeDesign.Request
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.store.UiStore
import kotlinx.coroutines.channels.Channel

class AppNodeMenu(
    context: Context,
    menuView: View,
    private val uiStore: UiStore,
    private val requests: Channel<Request>,
) : PopupMenu.OnMenuItemClickListener {
    private val menu = PopupMenu(context, menuView)

    fun show() {
        menu.show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.isCheckable)
            item.isChecked = !item.isChecked

        return when (item.itemId) {
            R.id.system_apps -> {
                uiStore.appNodeSystemApp = item.isChecked

                requests.trySend(Request.ToggleSystemApps)
                true
            }
            else -> false
        }
    }

    init {
        menu.menuInflater.inflate(R.menu.menu_app_node, menu.menu)

        menu.menu.findItem(R.id.system_apps).isChecked = uiStore.appNodeSystemApp

        menu.setOnMenuItemClickListener(this)
    }
}
// FEATURE-SYSTEM-MARK END
