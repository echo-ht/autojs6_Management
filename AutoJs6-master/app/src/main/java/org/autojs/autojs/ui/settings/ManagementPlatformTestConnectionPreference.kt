package org.autojs.autojs.ui.settings

import android.content.Context
import android.util.AttributeSet
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.network.ManagementPlatformClient
import org.autojs.autojs.theme.preference.MaterialPreference
import org.autojs.autojs.util.ViewUtils
import org.autojs.autojs6.R

class ManagementPlatformTestConnectionPreference : MaterialPreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    override fun onClick() {
        val address = Pref.getManagementPlatformServerAddress().trim()
        if (address.isEmpty()) {
            ViewUtils.showToast(prefContext, R.string.text_management_platform_server_address, true)
            super.onClick()
            return
        }
        ManagementPlatformClient.testConnection { success, message ->
            if (success) {
                ViewUtils.showToast(prefContext, R.string.text_management_platform_connection_success)
            } else {
                val detail = message ?: "unknown"
                val text = prefContext.getString(R.string.text_management_platform_connection_failed, detail)
                ViewUtils.showToast(prefContext, text, true)
            }
        }
        super.onClick()
    }
}
