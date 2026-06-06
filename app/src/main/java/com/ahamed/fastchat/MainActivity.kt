package com.ahamed.fastchat

import android.Manifest
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.TextWatcher
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var etPhoneNumber: EditText
    private lateinit var etMessage: TextInputEditText
    private lateinit var actvCountryCode: AutoCompleteTextView
    private lateinit var btnWhatsApp: MaterialButton
    private lateinit var btnBusiness: MaterialButton
    private lateinit var btnOpenTemplates: MaterialButton
    private lateinit var btnRefreshHistory: View
    private lateinit var rvCallLog: RecyclerView
    private lateinit var chipClipboard: Chip
    private lateinit var emptyState: View
    private lateinit var tvEmptyMsg: TextView

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private val prefs by lazy { getSharedPreferences("fastchat_prefs", MODE_PRIVATE) }

    private val countryCodes = listOf(
        "+1", "+7", "+20", "+27", "+31", "+33", "+34", "+39", "+44", "+48", "+49", "+52", "+55",
        "+60", "+61", "+62", "+65", "+81", "+82", "+86", "+90", "+91", "+92", "+94", "+98",
        "+212", "+234", "+254", "+966", "+971"
    )

    private var callLogList = emptyList<CallItem>()
    private val adapter by lazy {
        CallLogAdapter { rawNumber ->
            triggerHaptic(HapticFeedbackConstants.VIRTUAL_KEY)
            showAppSelectionBottomSheet(rawNumber)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) loadCallLog() else handlePermissionFailure()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViewBindings()
        initCountryCodePicker()
        setupListeners()
        syncStateWithPermission()
    }

    private fun initViewBindings() {
        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        etMessage = findViewById(R.id.etMessage)
        actvCountryCode = findViewById(R.id.actvCountryCode)
        btnWhatsApp = findViewById(R.id.btnWhatsApp)
        btnBusiness = findViewById(R.id.btnBusiness)
        btnOpenTemplates = findViewById(R.id.btnOpenTemplates)
        btnRefreshHistory = findViewById(R.id.btnRefreshHistory)
        rvCallLog = findViewById(R.id.rvCallLog)
        chipClipboard = findViewById(R.id.chipClipboard)
        emptyState = findViewById(R.id.emptyState)
        tvEmptyMsg = findViewById(R.id.tvEmptyMsg)

        val bottomSheet = findViewById<View>(R.id.bottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

        rvCallLog.layoutManager = LinearLayoutManager(this)
        rvCallLog.adapter = adapter
    }

    private fun initCountryCodePicker() {
        actvCountryCode.setOnClickListener { actvCountryCode.showDropDown() }
        
        val lastCode = prefs.getString("last_country_code", null)
        if (!lastCode.isNullOrEmpty()) actvCountryCode.setText(lastCode, false)
        else actvCountryCode.setText("+91", false)

        actvCountryCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s != null && s.isNotEmpty() && !s.startsWith("+")) {
                    actvCountryCode.setText("+$s")
                    actvCountryCode.setSelection(actvCountryCode.text.length)
                }
            }
            override fun afterTextChanged(s: Editable?) {
                prefs.edit { putString("last_country_code", s?.toString()) }
            }
        })
    }

    private fun setupListeners() {
        btnWhatsApp.setOnClickListener { processManualInput(isBusiness = false) }
        btnBusiness.setOnClickListener { processManualInput(isBusiness = true) }

        // RESTORED: Quick Reply Button Logic
        btnOpenTemplates.setOnClickListener {
            hideKeyboard()
            openTemplateDrawer(etMessage)
        }

        findViewById<View>(R.id.btnTopHistory)?.setOnClickListener {
            hideKeyboard()
            etPhoneNumber.clearFocus()
            etMessage.clearFocus()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        btnRefreshHistory.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.CONTEXT_CLICK)
            loadCallLog()
        }

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_DRAGGING || newState == BottomSheetBehavior.STATE_EXPANDED) {
                    hideKeyboard()
                    etPhoneNumber.clearFocus()
                    etMessage.clearFocus()
                }
            }
            override fun onSlide(bottomSheet: View, slideOffset: Float) {}
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED ||
                    bottomSheetBehavior.state == BottomSheetBehavior.STATE_HALF_EXPANDED) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                } else {
                    finish()
                }
            }
        })

        chipClipboard.setOnClickListener {
            val text = chipClipboard.tag as? String ?: ""
            handleSmartPaste(text)
            animateOut(chipClipboard)
            triggerHaptic(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.GESTURE_END else HapticFeedbackConstants.VIRTUAL_KEY)
        }

        actvCountryCode.setOnItemClickListener { parent, _, position, _ ->
            val code = parent.getItemAtPosition(position).toString()
            prefs.edit { putString("last_country_code", code) }
        }

        etPhoneNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                etPhoneNumber.error = null // VISUAL FIX: Clears error instantly on typing
                if (count > 1 && s?.startsWith("+") == true) handleSmartPaste(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // THE MAGIC: Controls the Template Drawer UI
    private fun openTemplateDrawer(targetEditText: EditText) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_templates, null)
        dialog.setContentView(view)

        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)

        val container = view.findViewById<android.widget.LinearLayout>(R.id.llTemplateContainer)
        val btnAddNew = view.findViewById<MaterialButton>(R.id.btnAddNewTemplate)

        val defaultTemplates = setOf("Hello 👋", "Send Price List 📄")
        val templates = prefs.getStringSet("templates", defaultTemplates) ?: defaultTemplates

        templates.sorted().forEach { templateText ->
            val cardView = layoutInflater.inflate(R.layout.item_template_card, container, false)
            val tvText = cardView.findViewById<TextView>(R.id.tvTemplateText)
            val btnDelete = cardView.findViewById<View>(R.id.btnDeleteTemplate)

            tvText.text = templateText

            cardView.setOnClickListener {
                targetEditText.setText(templateText)
                triggerHaptic(HapticFeedbackConstants.CLOCK_TICK)
                dialog.dismiss()
            }

            btnDelete.setOnClickListener {
                dialog.dismiss()
                showDeleteTemplateDialog(templateText)
            }

            container.addView(cardView)
        }

        btnAddNew.setOnClickListener {
            dialog.dismiss()
            showAddTemplateDialog()
        }

        dialog.show()
    }

    // --- SMART 10-DIGIT VALIDATION WITH RED UI ERROR ---
    private fun processManualInput(isBusiness: Boolean) {
        val raw = etPhoneNumber.text?.toString() ?: ""
        val cc = actvCountryCode.text.toString().filter { it.isDigit() }
        val clean = raw.filter { it.isDigit() }

        val isValid = if (cc == "91") {
            clean.length == 10
        } else {
            clean.length >= 7
        }

        if (isValid) {
            etPhoneNumber.error = null
            hideKeyboard()
            triggerHaptic(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
            openWhatsAppFinal(cc, clean, isBusiness)
        } else {
            triggerHaptic(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)

            val errorMsg = if (cc == "91") "Must be exactly 10 digits" else "Enter a valid number"
            etPhoneNumber.error = errorMsg
            etPhoneNumber.requestFocus()

            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractCountryCodeAndNumber(rawNumber: String): Pair<String, String> {
        var cleanNumber = rawNumber.filter { it.isDigit() }
        var detectedCc = actvCountryCode.text.toString().filter { it.isDigit() }

        if (rawNumber.startsWith("+")) {
            val matchedCode = countryCodes.filter { rawNumber.startsWith(it) }.maxByOrNull { it.length }
            if (matchedCode != null) {
                detectedCc = matchedCode.filter { it.isDigit() }
                cleanNumber = rawNumber.substring(matchedCode.length).filter { it.isDigit() }
                return Pair(detectedCc, cleanNumber)
            }
        }

        if (cleanNumber.startsWith("00")) {
            cleanNumber = cleanNumber.substring(2)
        } else if (cleanNumber.startsWith("0") && cleanNumber.length > 10) {
            cleanNumber = cleanNumber.substring(1)
        }

        if (detectedCc == "91" && cleanNumber.length == 12 && cleanNumber.startsWith("91")) {
            cleanNumber = cleanNumber.substring(2)
        }

        return Pair(detectedCc, cleanNumber)
    }

    private fun handleSmartPaste(text: String) {
        val (cc, cleanNum) = extractCountryCodeAndNumber(text)
        actvCountryCode.setText(if (cc.startsWith("+")) cc else "+$cc")
        prefs.edit { putString("last_country_code", actvCountryCode.text.toString()) }
        etPhoneNumber.setText(cleanNum)
        etPhoneNumber.setSelection(cleanNum.length)
    }

    private fun showAppSelectionBottomSheet(rawNumber: String) {
        val (cc, cleanNumber) = extractCountryCodeAndNumber(rawNumber)
        val formattedNumber = "+$cc $cleanNumber"

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_app_select, null)
        dialog.setContentView(view)

        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)

        val tvDialogNumber = view.findViewById<TextView>(R.id.tvDialogNumber)
        val etDialogMessage = view.findViewById<TextInputEditText>(R.id.etDialogMessage)
        val btnDialogTemplates = view.findViewById<MaterialButton>(R.id.btnDialogTemplates)
        val btnDialogWhatsApp = view.findViewById<MaterialButton>(R.id.btnDialogWhatsApp)
        val btnDialogBusiness = view.findViewById<MaterialButton>(R.id.btnDialogBusiness)

        tvDialogNumber.text = formattedNumber

        // RESTORED: Wire up the Template Drawer inside the dialog box
        btnDialogTemplates.setOnClickListener {
            hideKeyboard()
            openTemplateDrawer(etDialogMessage)
        }

        btnDialogWhatsApp.setOnClickListener {
            dialog.dismiss()
            openWhatsAppFinal(cc, cleanNumber, isBusiness = false, customMessage = etDialogMessage.text.toString())
        }

        btnDialogBusiness.setOnClickListener {
            dialog.dismiss()
            openWhatsAppFinal(cc, cleanNumber, isBusiness = true, customMessage = etDialogMessage.text.toString())
        }

        dialog.show()
    }

    private fun openWhatsAppFinal(countryCode: String, cleanNumber: String, isBusiness: Boolean, customMessage: String? = null) {
        val message = customMessage ?: (etMessage.text?.toString() ?: "")
        val baseUrl = "https://wa.me/$countryCode$cleanNumber"
        val finalUrl = if (message.isNotEmpty()) "$baseUrl?text=${URLEncoder.encode(message, "UTF-8")}" else baseUrl

        val intent = Intent(Intent.ACTION_VIEW, finalUrl.toUri()).apply {
            setPackage(if (isBusiness) "com.whatsapp.w4b" else "com.whatsapp")
        }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            intent.setPackage(null)
            try { startActivity(intent) } catch (_: Exception) {
                Toast.makeText(this, "WhatsApp is not installed.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddTemplateDialog() {
        val input = EditText(this).apply {
            hint = "Type your quick message..."
            setPadding(64, 32, 64, 32)
        }
        AlertDialog.Builder(this).setTitle("New Quick Message").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    val currentTemplates = prefs.getStringSet("templates", emptySet())?.toMutableSet() ?: mutableSetOf()
                    currentTemplates.add(newText)
                    prefs.edit { putStringSet("templates", currentTemplates) }
                    Toast.makeText(this, "Quick Reply Saved!", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showDeleteTemplateDialog(template: String) {
        AlertDialog.Builder(this).setTitle("Delete Template").setMessage("Remove '$template'?")
            .setPositiveButton("Delete") { _, _ ->
                val currentTemplates = prefs.getStringSet("templates", emptySet())?.toMutableSet() ?: mutableSetOf()
                currentTemplates.remove(template)
                prefs.edit { putStringSet("templates", currentTemplates) }
                Toast.makeText(this, "Quick Reply Deleted", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Keep", null).show()
    }

    private fun syncStateWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            loadCallLog()
        } else permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
    }

    override fun onResume() {
        super.onResume()
        smartPredictivePaste()
    }

    private fun smartPredictivePaste() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val hasText = clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true
        if (!hasText || !clipboard.hasPrimaryClip()) {
            if (chipClipboard.isVisible) animateOut(chipClipboard)
            return
        }
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        val digits = clipText.filter { it.isDigit() }
        if (digits.length in 7..15 && etPhoneNumber.text.isNullOrEmpty()) {
            chipClipboard.text = "Paste: $clipText"
            chipClipboard.tag = clipText
            if (!chipClipboard.isVisible) animateIn(chipClipboard)
        } else {
            if (chipClipboard.isVisible) animateOut(chipClipboard)
        }
    }

    private fun loadCallLog() {
        lifecycleScope.launch {
            val (newData, diffResult) = withContext(Dispatchers.IO) {
                val data = fetchRecentCalls()
                val diff = DiffUtil.calculateDiff(CallDiff(callLogList, data))
                data to diff
            }
            callLogList = newData
            adapter.items = newData
            diffResult.dispatchUpdatesTo(adapter)
            emptyState.isVisible = newData.isEmpty()
        }
    }

    private fun fetchRecentCalls(): List<CallItem> {
        val list = mutableListOf<CallItem>()
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE)
        try {
            contentResolver.query(CallLog.Calls.CONTENT_URI, projection, null, null, "${CallLog.Calls.DATE} DESC")?.use { cursor ->
                val numIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE)
                if (numIdx != -1 && nameIdx != -1 && dateIdx != -1) {
                    while (cursor.moveToNext() && list.size < 20) {
                        val num = cursor.getString(numIdx) ?: ""
                        val name = cursor.getString(nameIdx) ?: "Unknown"
                        val date = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(cursor.getLong(dateIdx)))
                        list.add(CallItem(name, num, date))
                    }
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun handlePermissionFailure() {
        if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.READ_CALL_LOG)) {
            tvEmptyMsg.text = "Call log access denied.\nTap here to allow in Settings."
            emptyState.setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = Uri.fromParts("package", packageName, null) })
            }
        } else tvEmptyMsg.text = "Permission needed to show recent calls."
        emptyState.isVisible = true
    }

    private fun hideKeyboard() {
        val view = this.currentFocus ?: window.decorView
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun triggerHaptic(effect: Int) { window.decorView.performHapticFeedback(effect) }

    private fun animateIn(v: View) {
        v.isVisible = true
        v.alpha = 0f
        v.translationY = 40f
        v.animate().alpha(1f).translationY(0f).setDuration(400).setInterpolator(OvershootInterpolator()).start()
    }

    private fun animateOut(v: View) {
        v.animate().alpha(0f).translationY(-40f).setDuration(250).withEndAction { v.isVisible = false }.start()
    }

    data class CallItem(val name: String, val number: String, val date: String)

    class CallDiff(private val old: List<CallItem>, private val new: List<CallItem>) : DiffUtil.Callback() {
        override fun getOldListSize() = old.size
        override fun getNewListSize() = new.size
        override fun areItemsTheSame(o: Int, n: Int) = old[o].number == new[n].number
        override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
    }

    inner class CallLogAdapter(var items: List<CallItem> = emptyList(), val onClick: (String) -> Unit) : RecyclerView.Adapter<CallLogAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvNumber: TextView = v.findViewById(R.id.tvNumber)
            val date: TextView = v.findViewById(R.id.tvDate)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_call_log, p, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]

            if (item.name == "Unknown") {
                h.tvName.text = item.number
                h.tvNumber.visibility = View.GONE
            } else {
                h.tvName.text = item.name
                h.tvNumber.text = item.number
                h.tvNumber.visibility = View.VISIBLE
            }

            h.date.text = item.date
            h.itemView.setOnClickListener { onClick(item.number) }
        }

        override fun getItemCount() = items.size
    }
}
