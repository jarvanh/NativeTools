package com.dede.nativetools.netspeed

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.*
import com.dede.nativetools.R
import com.dede.nativetools.main.applyBottomBarsInsets
import com.dede.nativetools.netspeed.service.NetSpeedNotificationHelper
import com.dede.nativetools.netspeed.service.NetSpeedServiceController
import com.dede.nativetools.netspeed.utils.NetFormatter
import com.dede.nativetools.ui.CustomWidgetLayoutSwitchPreference
import com.dede.nativetools.util.*
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.flow.firstOrNull

/**
 * 网速指示器设置页
 */
class NetSpeedFragment : PreferenceFragmentCompat(),
    Preference.OnPreferenceChangeListener,
    Preference.SummaryProvider<EditTextPreference> {

    private val configuration = NetSpeedConfiguration()

    private val controller by later { NetSpeedServiceController(requireContext()) }

    private lateinit var usageSwitchPreference: SwitchPreferenceCompat
    private lateinit var statusSwitchPreference: SwitchPreferenceCompat
    private lateinit var thresholdEditTextPreference: EditTextPreference

    private val activityResultLauncherCompat =
        ActivityResultLauncherCompat(this, ActivityResultContracts.StartActivityForResult())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        lifecycleScope.launchWhenCreated {
            val preferences = globalDataStore.data.firstOrNull() ?: return@launchWhenCreated
            configuration.updateFrom(preferences)

            val status = NetSpeedPreferences.status
            if (status) {
                checkNotificationEnable()
                controller.startService(true)
            }
            statusSwitchPreference.isChecked = status
        }

        controller.onCloseCallback = {
            statusSwitchPreference.isChecked = false
        }
        if (!Logic.checkAppOps(requireContext())) {
            usageSwitchPreference.isChecked = false
        }

        if (UI.isWideSize()) {
            applyBottomBarsInsets(listView)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStorePreference(requireContext())
        addPreferencesFromResource(R.xml.preference_net_speed)
        initGeneralPreferenceGroup()
        initNotificationPreferenceGroup()
    }

    private fun initGeneralPreferenceGroup() {
        statusSwitchPreference = requirePreference(NetSpeedPreferences.KEY_NET_SPEED_STATUS)
        statusSwitchPreference.onPreferenceChangeListener = this
        requirePreference<Preference>(NetSpeedPreferences.KEY_NET_SPEED_INTERVAL)
            .onPreferenceChangeListener = this

        thresholdEditTextPreference =
            requirePreference<EditTextPreference>(NetSpeedPreferences.KEY_NET_SPEED_HIDE_THRESHOLD).also {
                it.summaryProvider = this
                it.onPreferenceChangeListener = this
            }
        requirePreference<DropDownPreference>(NetSpeedPreferences.KEY_NET_SPEED_MIN_UNIT)
            .onPreferenceChangeListener = this
    }

    override fun provideSummary(preference: EditTextPreference): CharSequence {
        val bytes = preference.text?.toLongOrNull()
        return if (bytes != null) {
            if (bytes > 0) {
                val threshold = NetFormatter.format(
                    bytes,
                    NetFormatter.FLAG_FULL,
                    NetFormatter.ACCURACY_EXACT
                ).splicing()
                getString(R.string.summary_net_speed_hide_threshold, threshold)
            } else {
                getString(R.string.summary_net_speed_unhide)
            }
        } else {
            getString(R.string.summary_threshold_error)
        }
    }

    private fun initNotificationPreferenceGroup() {
        usageSwitchPreference =
            requirePreference<CustomWidgetLayoutSwitchPreference>(NetSpeedPreferences.KEY_NET_SPEED_USAGE).also {
                it.onPreferenceChangeListener = this
                it.bindCustomWidget = { holder ->
                    val imageView = holder.findViewById(R.id.iv_preference_help) as ImageView
                    imageView.setImageResource(R.drawable.ic_round_settings)
                    imageView.setOnClickListener {
                        findNavController().navigate(R.id.action_netSpeed_to_netUsageConfigFragment)
                    }
                }
            }

        requirePreference<CustomWidgetLayoutSwitchPreference>(NetSpeedPreferences.KEY_NET_SPEED_HIDE_LOCK_NOTIFICATION).let {
            it.onPreferenceChangeListener = this
            it.bindCustomWidget = { holder ->
                holder.findViewById(R.id.iv_preference_help)?.setOnClickListener {
                    requireContext().showHideLockNotificationDialog()
                }
            }
        }
        bindPreferenceChangeListener(
            this,
            NetSpeedPreferences.KEY_NET_SPEED_NOTIFY_CLICKABLE,
            NetSpeedPreferences.KEY_NET_SPEED_QUICK_CLOSEABLE,
            NetSpeedPreferences.KEY_NET_SPEED_HIDE_NOTIFICATION
        )

        updateNotificationPreferenceVisible()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        when (preference.key) {
            NetSpeedPreferences.KEY_NET_SPEED_STATUS -> {
                val status = newValue as Boolean
                if (status) controller.startService(true) else controller.stopService()
            }
            NetSpeedPreferences.KEY_NET_SPEED_INTERVAL -> {
                configuration.interval = (newValue as String).toInt()
                event(FirebaseAnalytics.Event.SELECT_ITEM) {
                    param(FirebaseAnalytics.Param.ITEM_NAME, configuration.interval.toLong())
                    param(FirebaseAnalytics.Param.CONTENT_TYPE, "刷新间隔")
                }
            }
            NetSpeedPreferences.KEY_NET_SPEED_HIDE_THRESHOLD -> {
                val strValue = (newValue as String)
                val hideThreshold = if (strValue.isEmpty()) 0 else strValue.toLongOrNull()
                if (hideThreshold == null) {
                    toast(R.string.summary_threshold_error)
                    return false
                }
                configuration.hideThreshold = hideThreshold
                event(FirebaseAnalytics.Event.SELECT_ITEM) {
                    param(FirebaseAnalytics.Param.ITEM_NAME, configuration.hideThreshold)
                    param(FirebaseAnalytics.Param.CONTENT_TYPE, "隐藏阈值")
                }

                val hideThresholdStr = hideThreshold.toString()
                if (hideThresholdStr != newValue) {
                    thresholdEditTextPreference.text = hideThresholdStr
                    return false
                }
            }

            NetSpeedPreferences.KEY_NET_SPEED_HIDE_LOCK_NOTIFICATION -> {
                configuration.hideLockNotification = newValue as Boolean
            }
            NetSpeedPreferences.KEY_NET_SPEED_USAGE -> {
                configuration.usage = newValue as Boolean
                checkOpsPermission()
            }
            NetSpeedPreferences.KEY_NET_SPEED_NOTIFY_CLICKABLE -> {
                configuration.notifyClickable = newValue as Boolean
            }
            NetSpeedPreferences.KEY_NET_SPEED_QUICK_CLOSEABLE -> {
                configuration.quickCloseable = newValue as Boolean
            }
            NetSpeedPreferences.KEY_NET_SPEED_HIDE_NOTIFICATION -> {
                configuration.hideNotification = newValue as Boolean
                event(FirebaseAnalytics.Event.SELECT_ITEM) {
                    param(
                        FirebaseAnalytics.Param.ITEM_NAME,
                        configuration.hideNotification.toString()
                    )
                    param(FirebaseAnalytics.Param.CONTENT_TYPE, "隐藏通知")
                }
            }
            NetSpeedPreferences.KEY_NET_SPEED_MIN_UNIT -> {
                configuration.minUnit = (newValue as String).toInt()
            }
            else -> return true
        }
        controller.updateConfiguration(configuration)
        return true
    }

    private fun updateNotificationPreferenceVisible() {
        if (!NetSpeedNotificationHelper.itSSAbove(requireContext())) {
            return
        }
        val keys = arrayOf(
            NetSpeedPreferences.KEY_NET_SPEED_HIDE_NOTIFICATION,
            NetSpeedPreferences.KEY_NET_SPEED_USAGE,
            NetSpeedPreferences.KEY_NET_SPEED_NOTIFY_CLICKABLE,
            NetSpeedPreferences.KEY_NET_SPEED_QUICK_CLOSEABLE
        )
        for (key in keys) {
            requirePreference<Preference>(key).isVisible = false
        }
    }

    override fun onDestroyView() {
        controller.unbindService()
        super.onDestroyView()
    }

    private fun checkOpsPermission() {
        Logic.requestOpsPermission(requireContext(), activityResultLauncherCompat, {
            usageSwitchPreference.isChecked = true
        }) {
            usageSwitchPreference.isChecked = false
        }
    }

    private fun checkNotificationEnable() {
        val context = requireContext()
        val areNotificationsEnabled = NetSpeedNotificationHelper.areNotificationEnabled(context)
        val dontAskNotify = NetSpeedPreferences.dontAskNotify
        if (dontAskNotify || areNotificationsEnabled) {
            return
        }
        context.showNotificationDisableDialog()
    }

}