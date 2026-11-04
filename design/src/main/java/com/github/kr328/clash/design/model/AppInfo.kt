package com.github.kr328.clash.design.model

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val installTime: Long,
    val updateDate: Long,
    // FEATURE-SYSTEM-MARK: true for system apps (FLAG_SYSTEM or
    // FLAG_UPDATED_SYSTEM_APP). Row layouts show a 系统应用 marker
    // from this field; default false keeps any other construction
    // site compiling and treats unknown apps as user apps.
    val systemApp: Boolean = false,
)