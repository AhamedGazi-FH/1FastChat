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
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
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
import com.google.android.material.textfield.TextInputLayout
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
    private lateinit var btnPrimaryAction: MaterialButton
    private lateinit var btnSecondaryAction: MaterialButton
    private lateinit var btnRefreshHistory: View
    private lateinit var rvCallLog: RecyclerView
    private lateinit var emptyState: View
    private lateinit var tvEmptyMsg: TextView
    
    // Expert UX Additions
    private lateinit var llContextArea: View
    private lateinit var tvContextSource: TextView
    private lateinit var chipPredictive: Chip
    private lateinit var hsvFlashHistory: View
    private lateinit var llFlashBubbles: ViewGroup
    private lateinit var chipAddMessage: Chip
    private lateinit var chipQuickReplies: Chip
    private lateinit var tilMessage: TextInputLayout
    private lateinit var tilPhoneNumber: TextInputLayout

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<View>

    private val prefs by lazy { getSharedPreferences("fastchat_prefs", MODE_PRIVATE) }

    private val countryCodes = listOf(
        "+1", "+7", "+20", "+27", "+31", "+33", "+34", "+39", "+44", "+48", "+49", "+52", "+55",
        "+60", "+61", "+62", "+65", "+81", "+82", "+86", "+90", "+91", "+92", "+94", "+98",
        "+212", "+234", "+254", "+966", "+971"
    )

    private var callLogList = emptyList<CallItem>()
    private val adapter by lazy {
        CallLogAdapter(
            onItemClick = { rawNumber ->
                triggerHaptic(HapticFeedbackConstants.VIRTUAL_KEY)
                showAppSelectionBottomSheet(rawNumber)
            },
            onQuickChat = { rawNumber ->
                triggerHaptic(HapticFeedbackConstants.CONFIRM)
                val (cc, num) = extractCountryCodeAndNumber(rawNumber)
                openWhatsAppFinal(cc, num, isBusiness = isLastUsedBusiness())
            }
        )
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
        updateActionButtonsUI()
    }

    private fun initViewBindings() {
        etPhoneNumber = findViewById(R.id.etPhoneNumber)
        etMessage = findViewById(R.id.etMessage)
        actvCountryCode = findViewById(R.id.actvCountryCode)
        btnPrimaryAction = findViewById(R.id.btnPrimaryAction)
        btnSecondaryAction = findViewById(R.id.btnSecondaryAction)
        btnRefreshHistory = findViewById(R.id.btnRefreshHistory)
        rvCallLog = findViewById(R.id.rvCallLog)
        emptyState = findViewById(R.id.emptyState)
        tvEmptyMsg = findViewById(R.id.tvEmptyMsg)

        // Expert Bindings
        llContextArea = findViewById(R.id.llContextArea)
        tvContextSource = findViewById(R.id.tvContextSource)
        chipPredictive = findViewById(R.id.chipPredictive)
        hsvFlashHistory = findViewById(R.id.hsvFlashHistory)
        llFlashBubbles = findViewById(R.id.llFlashBubbles)
        chipAddMessage = findViewById(R.id.chipAddMessage)
        chipQuickReplies = findViewById(R.id.chipQuickReplies)
        tilMessage = findViewById(R.id.tilMessage)
        tilPhoneNumber = findViewById(R.id.tilPhoneNumber)

        val bottomSheet = findViewById<View>(R.id.bottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)

        rvCallLog.layoutManager = LinearLayoutManager(this)
        rvCallLog.adapter = adapter
        
        // [4] Gesture-Powered History (Swipe to Chat)
        setupSwipeToChat()
    }

    private fun setupSwipeToChat() {
        val swipeHandler = object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(0, 
            androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT) {
            override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val item = callLogList[position]
                val (cc, num) = extractCountryCodeAndNumber(item.number)
                
                triggerHaptic(HapticFeedbackConstants.CONFIRM)
                // EXPERT: Both directions = Business for 90% speed, unless user swipes VERY intentionally
                openWhatsAppFinal(cc, num, isBusiness = true)
                
                // Reset item in list
                adapter.notifyItemChanged(position)
            }
        }
        androidx.recyclerview.widget.ItemTouchHelper(swipeHandler).attachToRecyclerView(rvCallLog)
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
        // [Micro-Interaction] Button Squish Animation
        btnPrimaryAction.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
            false
        }

        // [1] Mind-Reading Trigger: Inside-Box History Icon
        tilPhoneNumber.setEndIconOnClickListener {
            expandHistory()
        }

        // [2] Frustration Trigger: Double-Tap to expand history
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                expandHistory()
                return true
            }
        })
        etPhoneNumber.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }

        // [10] Thumb-Zone Actions
        btnPrimaryAction.setOnClickListener { 
            processManualInput(isBusiness = isLastUsedBusiness()) 
        }
        btnSecondaryAction.setOnClickListener { 
            processManualInput(isBusiness = !isLastUsedBusiness()) 
        }

        // [5] Smart Keyboard "Done" Action
        etPhoneNumber.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                processManualInput(isBusiness = isLastUsedBusiness())
                true
            } else false
        }

        // [12] Progressive Disclosure: Action Pills
        chipAddMessage.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.VIRTUAL_KEY)
            if (tilMessage.isVisible) {
                tilMessage.isVisible = false
                chipAddMessage.text = "Add Message"
            } else {
                tilMessage.isVisible = true
                chipAddMessage.text = "Remove Message"
                etMessage.requestFocus()
                showKeyboard(etMessage)
            }
        }

        chipQuickReplies.setOnClickListener {
            triggerHaptic(HapticFeedbackConstants.VIRTUAL_KEY)
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

        chipPredictive.setOnClickListener {
            val text = chipPredictive.tag as? String ?: ""
            handleSmartPaste(text, "Recent Call")
            triggerHaptic(HapticFeedbackConstants.GESTURE_END)
        }

        etPhoneNumber.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                etPhoneNumber.error = null
                
                // Toggle History Icon: Only show when empty
                tilPhoneNumber.isEndIconVisible = s.isNullOrEmpty()

                // [11] Auto-Format on Paste / Typing
                if (count > 1) {
                    val filtered = s.toString().filter { it.isDigit() || it == '+' }
                    if (filtered != s.toString()) {
                        etPhoneNumber.setText(filtered)
                        etPhoneNumber.setSelection(filtered.length)
                    }
                }
                
                // [7] Haptic Language: Valid Number Pulse
                val clean = s.toString().filter { it.isDigit() }
                if (clean.length == 10 && actvCountryCode.text.toString() == "+91") {
                    triggerHaptic(HapticFeedbackConstants.CONFIRM)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updateActionButtonsUI() {
        // [6] Intent Persistence: Always prioritize Business for 90% use case
        btnPrimaryAction.text = "Chat (Business)"
        btnSecondaryAction.text = "Use Standard WhatsApp"
    }

    private fun isLastUsedBusiness() = true // Hardcoded to true for 90% preference logic

    private fun expandHistory() {
        hideKeyboard()
        etPhoneNumber.clearFocus()
        etMessage.clearFocus()
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        triggerHaptic(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun setLastUsedBusiness(isBiz: Boolean) {
        // Preference kept but primary action is fixed to reduce decision fatigue
        prefs.edit { putBoolean("last_used_business", isBiz) }
    }

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
            cardView.findViewById<TextView>(R.id.tvTemplateText).text = templateText
            cardView.setOnClickListener {
                targetEditText.setText(templateText)
                if (!tilMessage.isVisible && targetEditText == etMessage) {
                    tilMessage.isVisible = true
                    chipAddMessage.text = "Remove Message"
                }
                triggerHaptic(HapticFeedbackConstants.CLOCK_TICK)
                dialog.dismiss()
            }
            cardView.findViewById<View>(R.id.btnDeleteTemplate).setOnClickListener {
                dialog.dismiss()
                showDeleteTemplateDialog(templateText)
            }
            container.addView(cardView)
        }
        btnAddNew.setOnClickListener { dialog.dismiss(); showAddTemplateDialog() }
        dialog.show()
    }

    private fun processManualInput(isBusiness: Boolean) {
        val raw = etPhoneNumber.text?.toString() ?: ""
        val cc = actvCountryCode.text.toString().filter { it.isDigit() }
        val clean = raw.filter { it.isDigit() }

        val isValid = if (cc == "91") clean.length == 10 else clean.length >= 7

        if (isValid) {
            setLastUsedBusiness(isBusiness)
            hideKeyboard()
            triggerHaptic(HapticFeedbackConstants.CONFIRM)
            openWhatsAppFinal(cc, clean, isBusiness)
        } else {
            triggerHaptic(HapticFeedbackConstants.REJECT)
            etPhoneNumber.error = if (cc == "91") "Must be 10 digits" else "Invalid number"
            etPhoneNumber.requestFocus()
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
        if (cleanNumber.startsWith("00")) cleanNumber = cleanNumber.substring(2)
        else if (cleanNumber.startsWith("0") && cleanNumber.length > 10) cleanNumber = cleanNumber.substring(1)
        if (detectedCc == "91" && cleanNumber.length == 12 && cleanNumber.startsWith("91")) cleanNumber = cleanNumber.substring(2)

        return Pair(detectedCc, cleanNumber)
    }

    private fun handleSmartPaste(text: String, sourceName: String) {
        val (cc, cleanNum) = extractCountryCodeAndNumber(text)
        actvCountryCode.setText(if (cc.startsWith("+")) cc else "+$cc")
        prefs.edit { putString("last_country_code", actvCountryCode.text.toString()) }
        etPhoneNumber.setText(cleanNum)
        etPhoneNumber.setSelection(cleanNum.length)
        
        // [8] Morphing UI: Show Context
        tvContextSource.text = "From $sourceName"
        llContextArea.isVisible = true
        llContextArea.alpha = 0f
        llContextArea.animate().alpha(1f).setDuration(300).start()
    }

    private fun showAppSelectionBottomSheet(rawNumber: String) {
        val (cc, cleanNumber) = extractCountryCodeAndNumber(rawNumber)
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_bottom_sheet_app_select, null)
        dialog.setContentView(view)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            ?.setBackgroundResource(android.R.color.transparent)

        view.findViewById<TextView>(R.id.tvDialogNumber).text = "+$cc $cleanNumber"
        val etDialogMsg = view.findViewById<TextInputEditText>(R.id.etDialogMessage)
        
        view.findViewById<MaterialButton>(R.id.btnDialogTemplates).setOnClickListener {
            openTemplateDrawer(etDialogMsg)
        }
        view.findViewById<MaterialButton>(R.id.btnDialogWhatsApp).setOnClickListener {
            dialog.dismiss()
            setLastUsedBusiness(false)
            openWhatsAppFinal(cc, cleanNumber, false, etDialogMsg.text.toString())
        }
        view.findViewById<MaterialButton>(R.id.btnDialogBusiness).setOnClickListener {
            dialog.dismiss()
            setLastUsedBusiness(true)
            openWhatsAppFinal(cc, cleanNumber, true, etDialogMsg.text.toString())
        }
        dialog.show()
    }

    private fun openWhatsAppFinal(countryCode: String, cleanNumber: String, isBusiness: Boolean, customMessage: String? = null) {
        val message = customMessage ?: (if (tilMessage.isVisible) etMessage.text?.toString() ?: "" else "")
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
                Toast.makeText(this, "WhatsApp not found", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddTemplateDialog() {
        val input = EditText(this).apply { hint = "Quick message..."; setPadding(64, 32, 64, 32) }
        AlertDialog.Builder(this).setTitle("New Template").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    val cur = prefs.getStringSet("templates", emptySet())?.toMutableSet() ?: mutableSetOf()
                    cur.add(newText); prefs.edit { putStringSet("templates", cur) }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun showDeleteTemplateDialog(template: String) {
        AlertDialog.Builder(this).setTitle("Delete?").setMessage("Remove '$template'?")
            .setPositiveButton("Delete") { _, _ ->
                val cur = prefs.getStringSet("templates", emptySet())?.toMutableSet() ?: mutableSetOf()
                cur.remove(template); prefs.edit { putStringSet("templates", cur) }
            }.setNegativeButton("Keep", null).show()
    }

    private fun syncStateWithPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            loadCallLog()
        } else permissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
    }

    override fun onResume() {
        super.onResume()
        // [1] Zero-Click Clipboard
        smartPredictivePaste()
    }

    private fun smartPredictivePaste() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        if (!clipboard.hasPrimaryClip()) return
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        val digits = clipText.filter { it.isDigit() }
        
        if (digits.length in 7..15 && etPhoneNumber.text.isNullOrEmpty()) {
            handleSmartPaste(clipText, "Clipboard")
            triggerHaptic(HapticFeedbackConstants.CONFIRM)
        }
    }

    private fun loadCallLog() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) { fetchRecentCalls() }
            
            // [2] Predictive History Injection
            if (data.isNotEmpty()) {
                val lastCall = data.first()
                // Simple logic: if fetched in last 2 mins (not strictly possible with CallLog.Calls.DATE without more math, but we show the latest)
                chipPredictive.text = "Call: ${lastCall.name}"
                chipPredictive.tag = lastCall.number
                chipPredictive.isVisible = true
                llContextArea.isVisible = true
            }

            // [9] Flash History Bubbles (Last 3)
            llFlashBubbles.removeAllViews()
            data.take(3).forEach { item ->
                val chip = Chip(this@MainActivity).apply {
                    text = if (item.name == "Unknown") item.number else item.name
                    setOnClickListener { handleSmartPaste(item.number, "Recent History") }
                    val params = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    params.marginEnd = 16 // Explicit margin
                    layoutParams = params
                }
                llFlashBubbles.addView(chip)
            }
            hsvFlashHistory.isVisible = llFlashBubbles.childCount > 0

            callLogList = data
            adapter.items = data
            adapter.notifyDataSetChanged()
            emptyState.isVisible = data.isEmpty()
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
                while (cursor.moveToNext() && list.size < 20) {
                    val num = cursor.getString(numIdx) ?: ""
                    val name = cursor.getString(nameIdx) ?: "Unknown"
                    val date = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cursor.getLong(dateIdx)))
                    list.add(CallItem(name, num, date))
                }
            }
        } catch (_: Exception) {}
        return list
    }

    private fun handlePermissionFailure() {
        tvEmptyMsg.text = "Grant Call Log permission in Settings."
        emptyState.isVisible = true
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun triggerHaptic(effect: Int) {
        // [2026] Micro-Tick Haptics: High-frequency, crisp pulses
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val microTick = when(effect) {
                HapticFeedbackConstants.CONFIRM -> HapticFeedbackConstants.VIRTUAL_KEY
                HapticFeedbackConstants.REJECT -> HapticFeedbackConstants.CONTEXT_CLICK
                else -> HapticFeedbackConstants.CLOCK_TICK
            }
            window.decorView.performHapticFeedback(microTick)
        } else {
            window.decorView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    data class CallItem(val name: String, val number: String, val date: String)

    inner class CallLogAdapter(
        var items: List<CallItem> = emptyList(), 
        val onItemClick: (String) -> Unit,
        val onQuickChat: (String) -> Unit
    ) : RecyclerView.Adapter<CallLogAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvNumber: TextView = v.findViewById(R.id.tvNumber)
            val date: TextView = v.findViewById(R.id.tvDate)
            val btnQuickChat: View = v.findViewById(R.id.btnQuickChat)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) = VH(LayoutInflater.from(p.context).inflate(R.layout.item_call_log, p, false))

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.tvName.text = if (item.name == "Unknown") item.number else item.name
            h.tvNumber.text = item.number
            h.tvNumber.isVisible = item.name != "Unknown"
            h.date.text = item.date
            
            h.itemView.setOnClickListener { onItemClick(item.number) }
            h.btnQuickChat.setOnClickListener { onQuickChat(item.number) }
        }

        override fun getItemCount() = items.size
    }
}
