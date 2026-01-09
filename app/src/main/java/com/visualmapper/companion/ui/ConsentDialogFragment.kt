package com.visualmapper.companion.ui

import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.DialogFragment
import com.visualmapper.companion.R
import com.visualmapper.companion.security.ConsentManager
import com.visualmapper.companion.security.SensitiveDataDetector

/**
 * Consent Dialog Fragment
 *
 * Shows when a new app is detected that needs consent.
 * Explains what data will be collected and lets user choose level.
 */
class ConsentDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_PACKAGE_NAME = "package_name"
        private const val ARG_IS_SENSITIVE = "is_sensitive"

        fun newInstance(packageName: String, isSensitive: Boolean): ConsentDialogFragment {
            return ConsentDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PACKAGE_NAME, packageName)
                    putBoolean(ARG_IS_SENSITIVE, isSensitive)
                }
            }
        }
    }

    private lateinit var packageName: String
    private var isSensitive: Boolean = false

    var onConsentGranted: ((ConsentManager.ConsentLevel) -> Unit)? = null
    var onConsentDenied: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        packageName = arguments?.getString(ARG_PACKAGE_NAME) ?: ""
        isSensitive = arguments?.getBoolean(ARG_IS_SENSITIVE) ?: false
        setStyle(STYLE_NO_FRAME, R.style.ConsentDialogTheme)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_consent, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imgAppIcon: ImageView = view.findViewById(R.id.imgAppIcon)
        val txtAppName: TextView = view.findViewById(R.id.txtAppName)
        val txtPackageName: TextView = view.findViewById(R.id.txtPackageName)
        val txtWarning: TextView = view.findViewById(R.id.txtWarning)
        val layoutWarning: View = view.findViewById(R.id.layoutWarning)
        val txtDataCategories: TextView = view.findViewById(R.id.txtDataCategories)
        val radioGroup: RadioGroup = view.findViewById(R.id.radioConsentLevel)
        val radioNone: RadioButton = view.findViewById(R.id.radioNone)
        val radioBasic: RadioButton = view.findViewById(R.id.radioBasic)
        val radioFull: RadioButton = view.findViewById(R.id.radioFull)
        val btnAllow: Button = view.findViewById(R.id.btnAllow)
        val btnDeny: Button = view.findViewById(R.id.btnDeny)

        // Get app info
        val pm = requireContext().packageManager
        val appName = try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }

        val appIcon: Drawable? = try {
            pm.getApplicationIcon(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        // Set app info
        txtAppName.text = appName
        txtPackageName.text = packageName
        appIcon?.let { imgAppIcon.setImageDrawable(it) }

        // Show warning for sensitive apps
        if (isSensitive) {
            layoutWarning.visibility = View.VISIBLE
            txtWarning.text = "This app may contain sensitive data (banking, passwords, etc.)\n\n" +
                "• Password fields will NEVER be captured\n" +
                "• Credit card numbers will be masked\n" +
                "• We recommend 'Basic' level for this app"

            // Pre-select Basic for sensitive apps
            radioBasic.isChecked = true
        } else {
            layoutWarning.visibility = View.GONE
            radioFull.isChecked = true
        }

        // Data categories explanation
        txtDataCategories.text = """
            What we access:

            • Basic: UI element positions only (no text)
            • Full: Visible text from labels and buttons

            What we NEVER access:
            • Password field content
            • Credit card numbers
            • Biometric data
        """.trimIndent()

        // Button handlers
        btnAllow.setOnClickListener {
            val level = when (radioGroup.checkedRadioButtonId) {
                R.id.radioNone -> ConsentManager.ConsentLevel.NONE
                R.id.radioBasic -> ConsentManager.ConsentLevel.BASIC
                R.id.radioFull -> ConsentManager.ConsentLevel.FULL
                else -> ConsentManager.ConsentLevel.BASIC
            }
            onConsentGranted?.invoke(level)
            dismiss()
        }

        btnDeny.setOnClickListener {
            onConsentDenied?.invoke()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        // Make dialog wider
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
