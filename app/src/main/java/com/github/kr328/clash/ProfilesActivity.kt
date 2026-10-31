package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.util.intent
import com.github.kr328.clash.common.util.setUUID
import com.github.kr328.clash.common.util.ticker
import androidx.activity.result.contract.ActivityResultContracts
import com.github.kr328.clash.design.ProfilesDesign
import com.github.kr328.clash.design.ui.ToastDuration
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.TimeUnit
import com.github.kr328.clash.design.R

class ProfilesActivity : BaseActivity<ProfilesDesign>() {
    override suspend fun main() {
        val design = ProfilesDesign(this)

        setContentDesign(design)

        val ticker = ticker(TimeUnit.MINUTES.toMillis(1))

        while (isActive) {
            select<Unit> {
                events.onReceive {
                    when (it) {
                        Event.ActivityStart, Event.ProfileChanged -> {
                            design.fetch()
                        }
                        else -> Unit
                    }
                }
                design.requests.onReceive {
                    when (it) {
                        ProfilesDesign.Request.Create ->
                            startActivity(NewProfileActivity::class.intent)
                        ProfilesDesign.Request.UpdateAll ->
                            withProfile {
                                try {
                                    queryAll().forEach { p ->
                                        if (p.imported && p.type != Profile.Type.File)
                                            update(p.uuid)
                                    }
                                }
                                finally {
                                    withContext(Dispatchers.Main) {
                                        design.finishUpdateAll();
                                    }
                                }
                            }
                        is ProfilesDesign.Request.Update ->
                            withProfile { update(it.profile.uuid) }
                        is ProfilesDesign.Request.Delete ->
                            withProfile { delete(it.profile.uuid) }
                        is ProfilesDesign.Request.Edit ->
                            startActivity(PropertiesActivity::class.intent.setUUID(it.profile.uuid))
                        is ProfilesDesign.Request.Active -> {
                            withProfile {
                                if (it.profile.imported) {
                                    // FEATURE-PROFILE-AUTORESTART BEGIN: switching profile while
                                    // VPN is running must actually switch the core. Previously
                                    // setActive only changed the stored active profile and
                                    // refreshed the UI; the running core kept the old profile
                                    // until the user stopped and started VPN manually, so a
                                    // switch from the independent config to the main config
                                    // kept using the openworld node. Restart here, and only
                                    // here: no restart when VPN is not running (next start
                                    // loads the new profile anyway), and no restart when the
                                    // tapped profile is already active.
                                    val oldActive = queryActive()?.uuid
                                    setActive(it.profile)
                                    if (clashRunning && oldActive != null &&
                                        oldActive != it.profile.uuid
                                    ) {
                                        // withProfile runs on Dispatchers.IO; run the
                                        // restart on Main like AccessControlActivity does,
                                        // so service start/stop and the VPN prompt run in
                                        // the same thread context as a manual start.
                                        withContext(Dispatchers.Main) {
                                            design.showToast(
                                                R.string.toast_profile_restarting,
                                                ToastDuration.Long
                                            )
                                            stopClashService()
                                            var waited = 0
                                            while (clashRunning && waited < 10000) {
                                                delay(200)
                                                waited += 200
                                            }
                                            if (!clashRunning) {
                                                try {
                                                    val vpnRequest = startClashService()
                                                    if (vpnRequest != null) {
                                                        val result = startActivityForResult(
                                                            ActivityResultContracts.StartActivityForResult(),
                                                            vpnRequest
                                                        )
                                                        if (result.resultCode == RESULT_OK)
                                                            startClashService()
                                                        else
                                                            design.showToast(
                                                                R.string.toast_profile_restart_failed,
                                                                ToastDuration.Long
                                                            )
                                                    }
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    design.showToast(
                                                        R.string.unable_to_start_vpn,
                                                        ToastDuration.Long
                                                    )
                                                }
                                            } else {
                                                design.showToast(
                                                    R.string.toast_profile_restart_failed,
                                                    ToastDuration.Long
                                                )
                                            }
                                        }
                                    }
                                    // FEATURE-PROFILE-AUTORESTART END
                                } else
                                    design.requestSave(it.profile)
                            }
                        }
                        is ProfilesDesign.Request.Duplicate -> {
                            val uuid = withProfile { clone(it.profile.uuid) }

                            startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                        }
                        // FEATURE-5 BEGIN: per-profile node filter picker.
                        is ProfilesDesign.Request.FilterNodes ->
                            startActivity(NodeFilterActivity::class.intent.setUUID(it.profile.uuid))
                        // FEATURE-5 END
                        // FEATURE-PROFORDER: persist the dragged order.
                        is ProfilesDesign.Request.Reorder -> {
                            uiStore.profileOrder = it.uuids.joinToString(",")
                        }
                        is ProfilesDesign.Request.ShareQrCode ->
                            design.showProfileQrCode(it.profile)
                    }
                }
                if (activityStarted) {
                    ticker.onReceive {
                        design.updateElapsed()
                    }
                }
            }
        }
    }

    private suspend fun ProfilesDesign.fetch() {
        withProfile {
            // FEATURE-PROFORDER: apply the saved drag order; profiles not
            // in it (newly created) keep their relative order at the end.
            val order = uiStore.profileOrder.split(",")
                .filter { it.isNotBlank() }
                .withIndex().associate { it.value to it.index }
            val all = queryAll()
            patchProfiles(all.sortedWith(
                compareBy { order[it.uuid.toString()] ?: Int.MAX_VALUE }
            ))
        }
    }

    override fun onProfileUpdateCompleted(uuid: UUID?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_complete, name),
                ToastDuration.Long
            )
        }
    }
    override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {
        if(uuid == null)
            return;
        launch {
            var name: String? = null;
            withProfile {
                name = queryByUUID(uuid)?.name
            }
            design?.showToast(
                getString(R.string.toast_profile_updated_failed, name, reason),
                ToastDuration.Long
            ){
                setAction(R.string.edit) {
                    startActivity(PropertiesActivity::class.intent.setUUID(uuid))
                }
            }
        }
    }
}
