package org.autojs.autojs.ui.settings

import android.content.Context
import android.util.AttributeSet
import com.afollestad.materialdialogs.MaterialDialog
import org.autojs.autojs.core.pref.Pref
import org.autojs.autojs.network.ManagementPlatformClient
import org.autojs.autojs.theme.preference.MaterialPreference
import org.autojs.autojs6.R

class ManagementPlatformServerAddressPreference : MaterialPreference {

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

    constructor(context: Context) : super(context)

    init {
        summaryProvider = SummaryProvider<ManagementPlatformServerAddressPreference> {
            Pref.getManagementPlatformServerAddress()
        }
    }

    override fun onClick() {
        showEditDialog()
        super.onClick()
    }

    private fun showEditDialog() {
        val current = Pref.getManagementPlatformServerAddress()
        MaterialDialog.Builder(prefContext)
            .title(R.string.text_management_platform_server_address)
            .input(
                prefContext.getString(R.string.text_management_platform_server_address),
                current,
            ) { dialog, _ ->
                val input = dialog.inputEditText?.text?.toString()?.trim().orEmpty()
                Pref.setManagementPlatformServerAddress(input)
                ManagementPlatformClient.connectIfConfigured()
                dialog.dismiss()
                notifyChanged()
            }
            .negativeText(R.string.dialog_button_cancel)
            .negativeColorRes(R.color.dialog_button_default)
            .positiveText(R.string.dialog_button_confirm)
            .positiveColorRes(R.color.dialog_button_attraction)
            .autoDismiss(false)
            .show()
    }
}
