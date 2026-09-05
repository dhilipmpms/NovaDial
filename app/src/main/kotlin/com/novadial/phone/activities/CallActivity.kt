package com.novadial.phone.activities

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.postDelayed
import androidx.core.view.children
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.SimpleListItem
import com.novadial.phone.R
import com.novadial.phone.databinding.ActivityCallBinding
import com.novadial.phone.dialogs.DynamicBottomSheetChooserDialog
import com.novadial.phone.extensions.*
import com.novadial.phone.helpers.*
import com.novadial.phone.models.AudioRoute
import com.novadial.phone.models.CallContact
import com.novadial.phone.models.CallScreenViewModel
import com.novadial.phone.services.FloatingButtonService
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class CallActivity : SimpleActivity() {
    companion object {
        /**
         * True while this Activity instance is between onResume() and onPause().
         * Used by CallService to decide whether to bring CallActivity to the
         * foreground when a second call arrives while the user is outside the dialer.
         * @Volatile ensures cross-thread visibility (CallService runs on the
         * Telecom binder thread).
         */
        @Volatile
        var isInForeground: Boolean = false

        fun getStartIntent(context: Context): Intent {
            val openAppIntent = Intent(context, CallActivity::class.java)
            openAppIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            return openAppIntent
        }
    }

    private val binding by viewBinding(ActivityCallBinding::inflate)

    // ── Audio + ViewModel ────────────────────────────────────────────────────
    private val callAudioManager by lazy { CallAudioManager(this) }
    private val callScreenViewModel: CallScreenViewModel by viewModels()
    // ────────────────────────────────────────────────────────────────────────

    private var isSpeakerOn = false
    private var isMicrophoneOff = false
    private var isCallEnded = false
    private var callContact: CallContact? = null
    private var lastLoadedCallHandle: String? = null
    private var lastLoadedAvatarUri: String? = null
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var screenOnWakeLock: PowerManager.WakeLock? = null
    private var callDuration = 0
    private val callDurationHandler = Handler(Looper.getMainLooper())
    private var dragDownX = 0f
    private var stopAnimation = false
    private var viewsUnderDialpad = arrayListOf<Pair<View, Float>>()
    private var dialpadHeight = 0f
    private var audioRouteChooserDialog: DynamicBottomSheetChooserDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        if (CallManager.getPhoneState() == NoCall) {
            finish()
            return
        }

        setupEdgeToEdge(
            padTopSystem = listOf(binding.callHolder),
            padBottomSystem = listOf(binding.callHolder),
        )

        updateTextColors(binding.callHolder)
        binding.callHolder.setBackgroundColor(getNovaBackgroundColor())
        initButtons()
        addLockScreenFlags()
        CallManager.addListener(callCallback)
        updateCallContactInfo(CallManager.getPrimaryCall())

        // Observe the ViewModel's second-call event to trigger the call-waiting tone.
        // Using repeatOnLifecycle(STARTED) ensures collection is paused when the
        // Activity is in the background and resumed when it comes to the foreground.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                callScreenViewModel.secondCallEvent.collect { hasSecondCall ->
                    if (hasSecondCall) {
                        callAudioManager.startCallWaitingTone()
                        callScreenViewModel.consumeSecondCallEvent()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (CallManager.getPhoneState() == NoCall) {
            safeFinishAndRemoveTask()
            return
        }
        updateState()
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        if (CallManager.getPhoneState() == NoCall) {
            safeFinishAndRemoveTask()
            return
        }
        updateState()
        stopFloatingButton()
    }

    override fun onPause() {
        isInForeground = false
        super.onPause()
        if (!isCallEnded && CallManager.getPhoneState() != NoCall) {
            startFloatingButton(callContact?.number)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.removeListener(callCallback)
        callAudioManager.release()
        disableProximitySensor()
        stopFloatingButton()

        if (screenOnWakeLock?.isHeld == true) {
            screenOnWakeLock!!.release()
        }
    }

    override fun onBackPressedCompat(): Boolean {
        if (binding.dialpadWrapper.isVisible()) {
            hideDialpad()
            return true
        }

        val callState = CallManager.getState()
        if (callState == Call.STATE_CONNECTING || callState == Call.STATE_DIALING) {
            toast(R.string.call_is_being_connected)
            // Allow user to go back but show toast - they can return to call via notification
            return false
        }

        // Allow minimizing active call - user can return via notification
        return false
    }

    private fun initButtons() = binding.apply {
        if (config.disableSwipeToAnswer) {
            callDraggable.beGone()
            callDraggableBackground.beGone()
            callLeftArrow.beGone()
            callRightArrow.beGone()

            callDecline.setOnClickListener {
                endCall()
            }

            callAccept.setOnClickListener {
                acceptCall()
            }
        } else {
            handleSwipe()
        }

        callToggleMicrophone.setOnClickListener {
            toggleMicrophone()
        }

        callToggleSpeaker.setOnClickListener {
            changeCallAudioRoute()
        }

        callDialpad.setOnClickListener {
            toggleDialpadVisibility()
        }

        dialpadClose.setOnClickListener {
            hideDialpad()
        }

        callToggleHold.setOnClickListener {
            toggleHold()
        }

        callAdd.setOnClickListener {
            Intent(applicationContext, DialpadActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
                startActivity(this)
            }
        }

        callSwap.setOnClickListener {
            callAudioManager.playSwapHaptic()
            CallManager.swap()
        }

        callMerge.setOnClickListener {
            callAudioManager.playMergeHaptic()
            CallManager.merge()
        }

        onHoldSwap.setOnClickListener {
            callAudioManager.playSwapHaptic()
            CallManager.swap()
        }

        onHoldMerge.setOnClickListener {
            callAudioManager.playMergeHaptic()
            CallManager.merge()
        }

        onHoldEnd.setOnClickListener {
            CallManager.endHeldCall()
        }

        callWaitingAccept.setOnClickListener {
            callAudioManager.stopCallWaitingTone()
            CallManager.acceptRingingCall()
        }

        callWaitingDecline.setOnClickListener {
            callAudioManager.stopCallWaitingTone()
            CallManager.rejectRingingCall()
        }

        // Tint the call-waiting accept/reject buttons with semantic colors so they
        // are immediately distinguishable without relying on icon shape alone.
        binding.callWaitingDecline.background.applyColorFilter(getColor(R.color.call_reject_tint))
        binding.callWaitingDecline.applyColorFilter(android.graphics.Color.WHITE)
        binding.callWaitingAccept.background.applyColorFilter(getColor(R.color.call_accept_tint))
        binding.callWaitingAccept.applyColorFilter(android.graphics.Color.WHITE)

        // Card background and icon are already styled via StyleIncomingCallPopup / drawable;
        // phone icon inherits the text colour via applyColorFilter below.
        binding.callWaitingHolder.background.applyColorFilter(getProperBackgroundColor().lightenColor(2))
        binding.callWaitingIcon.applyColorFilter(getProperTextColor())

        callManage.setOnClickListener {
            startActivity(Intent(this@CallActivity, ConferenceActivity::class.java))
        }

        callEnd.setOnClickListener {
            endCall()
        }

        dialpadInclude.apply {
            dialpad0Holder.setOnClickListener { dialpadPressed('0') }
            dialpad1Holder.setOnClickListener { dialpadPressed('1') }
            dialpad2Holder.setOnClickListener { dialpadPressed('2') }
            dialpad3Holder.setOnClickListener { dialpadPressed('3') }
            dialpad4Holder.setOnClickListener { dialpadPressed('4') }
            dialpad5Holder.setOnClickListener { dialpadPressed('5') }
            dialpad6Holder.setOnClickListener { dialpadPressed('6') }
            dialpad7Holder.setOnClickListener { dialpadPressed('7') }
            dialpad8Holder.setOnClickListener { dialpadPressed('8') }
            dialpad9Holder.setOnClickListener { dialpadPressed('9') }

            arrayOf(
                dialpad0Holder,
                dialpad1Holder,
                dialpad2Holder,
                dialpad3Holder,
                dialpad4Holder,
                dialpad5Holder,
                dialpad6Holder,
                dialpad7Holder,
                dialpad8Holder,
                dialpad9Holder,
                dialpadPlusHolder,
                dialpadAsteriskHolder,
                dialpadHashtagHolder
            ).forEach {
                it.background = ResourcesCompat.getDrawable(resources, R.drawable.pill_background, theme)
                it.background?.alpha = LOWER_ALPHA_INT
            }

            dialpad0Holder.setOnLongClickListener { dialpadPressed('+'); true }
            dialpadAsteriskHolder.setOnClickListener { dialpadPressed('*') }
            dialpadHashtagHolder.setOnClickListener { dialpadPressed('#') }
            dialpadClearChar.setOnClickListener { clearChar(it) }
            dialpadClearChar.setOnLongClickListener { clearInput() }
        }

        dialpadWrapper.setBackgroundColor(
            if (isSystemInDarkMode()) {
                getProperBackgroundColor().lightenColor(2)
            } else {
                getProperBackgroundColor()
            }
        )

        arrayOf(dialpadClose, callSimImage, dialpadClearChar).forEach {
            it.applyColorFilter(getProperTextColor())
        }

        val bgColor = getProperBackgroundColor()
        val inactiveColor = getInactiveButtonColor()
        arrayOf(
            callToggleMicrophone, callToggleSpeaker, callDialpad,
            callToggleHold, callAdd, callSwap, callMerge, callManage
        ).forEach {
            it.applyColorFilter(bgColor.getContrastColor())
            it.background.applyColorFilter(inactiveColor)
        }

        arrayOf(
            callToggleMicrophone, callToggleSpeaker, callDialpad,
            callToggleHold, callAdd, callSwap, callMerge, callManage
        ).forEach { imageView ->
            imageView.setOnLongClickListener {
                if (!imageView.contentDescription.isNullOrEmpty()) {
                    toast(imageView.contentDescription.toString())
                }
                true
            }
        }

        callSimId.setTextColor(getProperTextColor().getContrastColor())
        dialpadInput.disableKeyboard()

        dialpadWrapper.onGlobalLayout {
            dialpadHeight = dialpadWrapper.height.toFloat()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSwipe() = binding.apply {
        var minDragX = 0f
        var maxDragX = 0f
        var initialDraggableX = 0f
        var initialLeftArrowX = 0f
        var initialRightArrowX = 0f
        var initialLeftArrowScaleX = 0f
        var initialLeftArrowScaleY = 0f
        var initialRightArrowScaleX = 0f
        var initialRightArrowScaleY = 0f
        var leftArrowTranslation = 0f
        var rightArrowTranslation = 0f

        val isRtl = isRTLLayout
        callAccept.onGlobalLayout {
            minDragX = if (isRtl) {
                callAccept.left.toFloat()
            } else {
                callDecline.left.toFloat()
            }

            maxDragX = if (isRtl) {
                callDecline.left.toFloat()
            } else {
                callAccept.left.toFloat()
            }

            initialDraggableX = callDraggable.left.toFloat()
            initialLeftArrowX = callLeftArrow.x
            initialRightArrowX = callRightArrow.x
            initialLeftArrowScaleX = callLeftArrow.scaleX
            initialLeftArrowScaleY = callLeftArrow.scaleY
            initialRightArrowScaleX = callRightArrow.scaleX
            initialRightArrowScaleY = callRightArrow.scaleY
            leftArrowTranslation = if (isRtl) {
                callAccept.x
            } else {
                -callDecline.x
            }

            rightArrowTranslation = if (isRtl) {
                -callAccept.x
            } else {
                callDecline.x
            }

            if (isRtl) {
                callLeftArrow.setImageResource(R.drawable.ic_chevron_right_vector)
                callRightArrow.setImageResource(R.drawable.ic_chevron_left_vector)
            }

            callLeftArrow.applyColorFilter(getColor(R.color.md_red_400))
            callRightArrow.applyColorFilter(getColor(R.color.md_green_400))

            startArrowAnimation(callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
            startArrowAnimation(callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
        }

        callDraggable.drawable.mutate().setTint(getProperTextColor())
        callDraggableBackground.drawable.mutate().setTint(getProperTextColor())

        var lock = false
        callDraggable.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.x
                    callDraggableBackground.animate().alpha(0f)
                    stopAnimation = true
                    callLeftArrow.animate().alpha(0f)
                    callRightArrow.animate().alpha(0f)
                    lock = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
                    callDraggable.animate().x(initialDraggableX).withEndAction {
                        callDraggableBackground.animate().alpha(0.2f)
                    }
                    callDraggable.setImageDrawable(getDrawable(R.drawable.ic_phone_down_vector))
                    callDraggable.drawable.mutate().setTint(getProperTextColor())
                    callLeftArrow.animate().alpha(1f)
                    callRightArrow.animate().alpha(1f)
                    stopAnimation = false
                    startArrowAnimation(callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
                    startArrowAnimation(callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
                }

                MotionEvent.ACTION_MOVE -> {
                    callDraggable.x = min(maxDragX, max(minDragX, event.rawX - dragDownX))
                    when {
                        callDraggable.x >= maxDragX - 50f -> {
                            if (!lock) {
                                lock = true
                                callDraggable.performHapticFeedback()
                                if (isRtl) {
                                    endCall()
                                } else {
                                    acceptCall()
                                }
                            }
                        }

                        callDraggable.x <= minDragX + 50f -> {
                            if (!lock) {
                                lock = true
                                callDraggable.performHapticFeedback()
                                if (isRtl) {
                                    acceptCall()
                                } else {
                                    endCall()
                                }
                            }
                        }

                        callDraggable.x > initialDraggableX -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_down_red_vector
                            } else {
                                R.drawable.ic_phone_green_vector
                            }
                            callDraggable.setImageDrawable(getDrawable(drawableRes))
                        }

                        callDraggable.x <= initialDraggableX -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_green_vector
                            } else {
                                R.drawable.ic_phone_down_red_vector
                            }
                            callDraggable.setImageDrawable(getDrawable(drawableRes))
                        }
                    }
                }
            }
            true
        }
    }

    private fun startArrowAnimation(arrow: ImageView, initialX: Float, initialScaleX: Float, initialScaleY: Float, translation: Float) {
        arrow.apply {
            alpha = 1f
            x = initialX
            scaleX = initialScaleX
            scaleY = initialScaleY
            animate()
                .alpha(0f)
                .translationX(translation)
                .scaleXBy(-0.5f)
                .scaleYBy(-0.5f)
                .setDuration(1000)
                .withEndAction {
                    if (!stopAnimation) {
                        startArrowAnimation(this, initialX, initialScaleX, initialScaleY, translation)
                    }
                }
        }
    }

    private fun dialpadPressed(char: Char) {
        CallManager.keypad(char)
        binding.dialpadInput.addCharacter(char)
    }

    private fun changeCallAudioRoute() {
        val supportAudioRoutes = CallManager.getSupportedAudioRoutes()
        if (supportAudioRoutes.contains(AudioRoute.BLUETOOTH)) {
            createOrUpdateAudioRouteChooser(supportAudioRoutes)
        } else {
            val targetSpeakerOn = !isSpeakerOn
            val targetRoute = if (targetSpeakerOn) AudioRoute.SPEAKER else AudioRoute.EARPIECE
            updateCallAudioState(targetRoute)
            val newRoute = if (targetSpeakerOn) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE
            CallManager.setAudioRoute(newRoute)
        }
    }

    private fun createOrUpdateAudioRouteChooser(routes: Array<AudioRoute>, create: Boolean = true) {
        val callAudioRoute = CallManager.getCallAudioRoute()
        val items = routes
            .sortedByDescending { it.route }
            .map {
                SimpleListItem(id = it.route, textRes = it.stringRes, imageRes = it.iconRes, selected = it == callAudioRoute)
            }
            .toTypedArray()

        if (audioRouteChooserDialog?.isVisible == true) {
            audioRouteChooserDialog?.updateChooserItems(items)
        } else if (create) {
            audioRouteChooserDialog = DynamicBottomSheetChooserDialog.createChooser(
                fragmentManager = supportFragmentManager,
                title = R.string.choose_audio_route,
                items = items
            ) {
                audioRouteChooserDialog = null
                CallManager.setAudioRoute(it.id)
            }
        }
    }

    private fun updateCallAudioState(route: AudioRoute?) {
        if (route != null) {
            isMicrophoneOff = audioManager.isMicrophoneMute
            updateMicrophoneButton()

            isSpeakerOn = route == AudioRoute.SPEAKER
            val supportedAudioRoutes = CallManager.getSupportedAudioRoutes()
            binding.callToggleSpeaker.apply {
                val bluetoothConnected = supportedAudioRoutes.contains(AudioRoute.BLUETOOTH)
                contentDescription = if (bluetoothConnected) {
                    getString(R.string.choose_audio_route)
                } else {
                    getString(if (isSpeakerOn) R.string.turn_speaker_off else R.string.turn_speaker_on)
                }

                // show speaker icon when a headset is connected, a headset icon maybe confusing to some
                if (route == AudioRoute.WIRED_HEADSET) {
                    setImageResource(R.drawable.ic_volume_down_vector)
                } else {
                    setImageResource(route.iconRes)
                }
            }
            toggleButtonColor(binding.callToggleSpeaker, enabled = route != AudioRoute.EARPIECE && route != AudioRoute.WIRED_HEADSET)
            createOrUpdateAudioRouteChooser(supportedAudioRoutes, create = false)

            if (isSpeakerOn) {
                disableProximitySensor()
            } else {
                enableProximitySensor()
            }
        }
    }

    private fun toggleMicrophone() {
        isMicrophoneOff = !isMicrophoneOff
        audioManager.isMicrophoneMute = isMicrophoneOff
        CallManager.inCallService?.setMuted(isMicrophoneOff)
        updateMicrophoneButton()
    }

    private fun updateMicrophoneButton() {
        toggleButtonColor(binding.callToggleMicrophone, isMicrophoneOff)
        binding.callToggleMicrophone.contentDescription = getString(if (isMicrophoneOff) R.string.turn_microphone_on else R.string.turn_microphone_off)
    }

    private fun toggleDialpadVisibility() {
        if (binding.dialpadWrapper.isVisible()) hideDialpad() else showDialpad()
    }

    private fun findVisibleViewsUnderDialpad(): Sequence<Pair<View, Float>> {
        return binding.ongoingCallHolder.children
            .filter { it is ImageView && it.isVisible() }
            .map { view -> Pair(view, view.alpha) }
    }

    private fun showDialpad() {
        binding.dialpadWrapper.apply {
            updatePadding(
                bottom = binding.root.bottom - binding.callEnd.top + resources.getDimensionPixelSize(R.dimen.activity_margin)
            )
            translationY = dialpadHeight
            alpha = 0f
            animate()
                .withStartAction { beVisible() }
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setDuration(200L)
                .alpha(1f)
                .translationY(0f)
                .start()
        }

        viewsUnderDialpad.clear()
        viewsUnderDialpad.addAll(findVisibleViewsUnderDialpad())
        viewsUnderDialpad.forEach { (view, _) ->
            view.run {
                animate().scaleX(0f).alpha(0f).withEndAction { beGone() }.duration = 250L
                animate().scaleY(0f).alpha(0f).withEndAction { beGone() }.duration = 250L
            }
        }
    }

    private fun hideDialpad() {
        binding.dialpadWrapper.animate()
            .withEndAction { binding.dialpadWrapper.beGone() }
            .setInterpolator(AccelerateDecelerateInterpolator())
            .setDuration(200L)
            .alpha(0f)
            .translationY(dialpadHeight)
            .start()

        viewsUnderDialpad.forEach { (view, alpha) ->
            view.run {
                animate().withStartAction { beVisible() }.setInterpolator(OvershootInterpolator()).scaleX(1f).alpha(alpha).duration = 250L
                animate().withStartAction { beVisible() }.setInterpolator(OvershootInterpolator()).scaleY(1f).alpha(alpha).duration = 250L
            }
        }
    }

    private fun toggleHold() {
        val isOnHold = CallManager.toggleHold()
        toggleButtonColor(binding.callToggleHold, isOnHold)
        binding.callToggleHold.contentDescription = getString(if (isOnHold) R.string.resume_call else R.string.hold_call)
        binding.holdStatusLabel.beInvisibleIf(!isOnHold)
    }

    private fun updateOtherPersonsInfo(contact: CallContact, avatarUri: String?) {
        binding.apply {
            val (name, _, number, numberLabel) = contact
            callerNameLabel.text = name.ifEmpty { getString(R.string.unknown_caller) }
            if (number.isNotEmpty() && number != name) {
                callerNumber.beVisible()
                callerNumber.text = if (numberLabel.isNotEmpty()) "$number - $numberLabel" else number
            } else {
                callerNumber.beGone()
            }

            callerAvatar.apply {
                if (avatarUri.isNullOrEmpty()) {
                    val bgColor = getProperPrimaryColor()
                    setBackgroundResource(R.drawable.circle_background)
                    setImageResource(R.drawable.ic_person_vector)
                    setPadding(resources.getDimensionPixelSize(R.dimen.activity_margin))
                    applyColorFilter(bgColor.getContrastColor())
                    background?.applyColorFilter(bgColor)
                } else {
                    if (!isFinishing && !isDestroyed) {
                        setPadding(0, 0, 0, 0)
                        clearColorFilter()
                        background = null
                        Glide.with(this)
                            .load(avatarUri)
                            .apply(RequestOptions.circleCropTransform())
                            .into(this)
                    }
                }
            }
        }
    }

    private fun getContactNameOrNumber(contact: CallContact): String {
        return contact.name.ifEmpty {
            contact.number.ifEmpty {
                getString(R.string.unknown_caller)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkCalledSIMCard() {
        try {
            val simLabels = getAvailableSIMCardLabels()
            if (simLabels.size > 1) {
                simLabels.forEachIndexed { index, sim ->
                    if (sim.handle == CallManager.getPrimaryCall()?.details?.accountHandle) {
                        binding.apply {
                            callSimId.text = sim.id.toString()
                            callSimId.beVisible()
                            callSimImage.beVisible()
                            val simColor = sim.color.adjustForContrast(getProperBackgroundColor())
                            callSimId.setTextColor(simColor.getContrastColor())
                            callSimImage.applyColorFilter(simColor)
                        }

                        val acceptDrawableId = when (index) {
                            0 -> R.drawable.ic_phone_one_vector
                            1 -> R.drawable.ic_phone_two_vector
                            else -> R.drawable.ic_phone_vector
                        }

                        val rippleBg = resources.getDrawable(R.drawable.ic_call_accept, theme) as RippleDrawable
                        val layerDrawable = rippleBg.findDrawableByLayerId(R.id.accept_call_background_holder) as LayerDrawable
                        layerDrawable.setDrawableByLayerId(R.id.accept_call_icon, getDrawable(acceptDrawableId))
                        binding.callAccept.setImageDrawable(rippleBg)
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun updateCallState(call: Call) {
        val state = call.getStateCompat()
        when (state) {
            Call.STATE_RINGING -> callRinging()
            Call.STATE_ACTIVE -> callStarted()
            Call.STATE_DISCONNECTED -> {
                if (CallManager.getPhoneState() == NoCall) {
                    endCall()
                }
            }
            Call.STATE_CONNECTING, Call.STATE_DIALING -> initOutgoingCallUI()
            Call.STATE_SELECT_PHONE_ACCOUNT -> showPhoneAccountPicker()
        }

        val statusTextId = when (state) {
            Call.STATE_RINGING -> R.string.is_calling
            Call.STATE_CONNECTING, Call.STATE_DIALING -> R.string.dialing
            else -> 0
        }

        // Merge is enabled only when Android Telecom actually reports conferencing
        // capability — not simply because the call is ACTIVE.
        val canMerge = !isCallEnded &&
            (call.conferenceableCalls.isNotEmpty() ||
                call.hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE))

        binding.apply {
            if (statusTextId != 0) {
                callStatusLabel.text = getString(statusTextId)
            } else {
                callStatusLabel.text = ""
            }

            callManage.beVisibleIf(!isCallEnded && call.hasCapability(Call.Details.CAPABILITY_MANAGE_CONFERENCE))
            setActionButtonEnabled(callSwap, enabled = !isCallEnded && state == Call.STATE_ACTIVE)
            setActionButtonEnabled(callMerge, enabled = canMerge)
        }
    }

    private fun updateState() {
        val phoneState = CallManager.getPhoneState()
        val ringingCall = CallManager.getRingingCall()
        val activeOrHeldCall = CallManager.getActiveCall() ?: CallManager.getHeldCall()

        when {
            // ── Scenario B/C/D: A waiting call arrived while a call is active/held ──
            // The main panel stays on the active call. The waiting banner overlays the
            // bottom. incomingCallHolder (full-screen swipe UI) must NOT be shown.
            ringingCall != null && activeOrHeldCall != null -> {
                binding.incomingCallHolder.beGone()
                // Show the banner with slide-up animation; the async callback only fills the name.
                showCallWaitingBanner()
                animateCallWaitingDim(dim = true)
                updateCallWaitingState(ringingCall)

                when (phoneState) {
                    is TwoCalls -> {
                        updateCallState(phoneState.active)
                        updateCallContactInfo(phoneState.active)
                        updateCallOnHoldState(phoneState.onHold)
                    }
                    is SingleCall -> {
                        updateCallState(phoneState.call)
                        updateCallContactInfo(phoneState.call)
                        updateCallOnHoldState(null)
                    }
                    else -> Unit
                }
            }

            // ── Scenario A first incoming / outgoing: only ringing call, no active call ──
            ringingCall != null -> {
                hideCallWaitingBanner()
                animateCallWaitingDim(dim = false)
                binding.incomingCallHolder.beVisible()
                updateCallState(ringingCall)
                updateCallContactInfo(ringingCall)
                updateCallOnHoldState(null)
            }

            // ── Scenarios E/F/G: no ringing call at all ──
            else -> {
                binding.incomingCallHolder.beGone()
                hideCallWaitingBanner()
                animateCallWaitingDim(dim = false)
                // Safety net: ensure the waiting tone is always stopped when there is
                // no ringing call, regardless of how the second call was dismissed.
                callAudioManager.stopCallWaitingTone()

                when (phoneState) {
                    is SingleCall -> {
                        updateCallState(phoneState.call)
                        updateCallContactInfo(phoneState.call)
                        updateCallOnHoldState(null)
                        val state = phoneState.call.getStateCompat()
                        val isSingleCallActionsEnabled = !isCallEnded &&
                            (state == Call.STATE_ACTIVE || state == Call.STATE_DISCONNECTED
                                || state == Call.STATE_DISCONNECTING || state == Call.STATE_HOLDING)
                        setActionButtonEnabled(binding.callToggleHold, isSingleCallActionsEnabled)
                        setActionButtonEnabled(binding.callAdd, isSingleCallActionsEnabled)
                    }
                    is TwoCalls -> {
                        updateCallState(phoneState.active)
                        updateCallContactInfo(phoneState.active)
                        updateCallOnHoldState(phoneState.onHold)
                    }
                    is NoCall -> {
                        endCall()
                        return
                    }
                }
            }
        }

        updateCallAudioState(CallManager.getCallAudioRoute())
    }

    private fun updateCallOnHoldState(call: Call?) {
        val hasCallOnHold = call != null
        if (hasCallOnHold) {
            val fastContact = getFastCallContact(applicationContext, call)
            if (fastContact.number.isNotEmpty()) {
                val name = getContactNameOrNumber(fastContact)
                binding.onHoldCallerName.text = name
            }

            getCallContact(applicationContext, call) { contact ->
                if (call != CallManager.getHeldCall()) {
                    return@getCallContact
                }
                runOnUiThread {
                    val name = getContactNameOrNumber(contact)
                    binding.onHoldCallerName.text = if (contact.number.isNotEmpty() && contact.number != contact.name) {
                        "$name (${contact.number})"
                    } else {
                        name
                    }
                }
            }
        }

        // Merge is only available when Telecom explicitly reports the capability on the
        // primary call — not simply because two calls exist.
        val primaryCall = CallManager.getPrimaryCall()
        val canMerge = hasCallOnHold && (
            primaryCall?.hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE) == true ||
                primaryCall?.conferenceableCalls?.isNotEmpty() == true
        )

        binding.apply {
            onHoldStatusHolder.beVisibleIf(hasCallOnHold)
            onHoldMerge.beVisibleIf(canMerge)
            controlsSingleCall.beVisibleIf(!hasCallOnHold)
            controlsTwoCalls.beVisibleIf(hasCallOnHold)
        }
    }

    private fun updateCallWaitingState(ringingCall: Call) {
        // Visibility is already set synchronously by updateState().
        // Show fast contact info immediately while async resolution completes.
        val fastContact = getFastCallContact(applicationContext, ringingCall)
        if (fastContact.number.isNotEmpty()) {
            val name = getContactNameOrNumber(fastContact)
            binding.callWaitingCallerName.text = name
        }

        getCallContact(applicationContext, ringingCall) { contact ->
            if (ringingCall.getStateCompat() != Call.STATE_RINGING) {
                return@getCallContact
            }
            runOnUiThread {
                binding.apply {
                    val name = getContactNameOrNumber(contact)
                    callWaitingCallerName.text = if (contact.number.isNotEmpty() && contact.number != contact.name) {
                        "$name (${contact.number})"
                    } else {
                        name
                    }
                }
            }
        }
    }

    private fun updateCallContactInfo(call: Call?) {
        val targetCall = call ?: CallManager.getPrimaryCall() ?: return
        val currentHandle = targetCall.details?.handle?.toString() ?: ""

        // 1. Instant update with fast call contact ONLY if no contact info has been loaded yet for this call
        if (callContact == null || lastLoadedCallHandle != currentHandle || callContact?.number.isNullOrEmpty()) {
            val fastContact = getFastCallContact(applicationContext, targetCall)
            if (fastContact.number.isNotEmpty()) {
                callContact = fastContact
                lastLoadedCallHandle = currentHandle
                updateOtherPersonsInfo(fastContact, null)
                checkCalledSIMCard()
            }
        }

        // 2. Async update with full contact name & photo
        getCallContact(applicationContext, targetCall) { contact ->
            val currentPrimaryCall = CallManager.getPrimaryCall()
            if (targetCall != currentPrimaryCall) {
                return@getCallContact
            }

            val avatar = if (!targetCall.isConference()) contact.photoUri else null

            // Avoid redundant UI updates if contact information and avatar URI haven't changed
            if (callContact?.name == contact.name &&
                callContact?.number == contact.number &&
                callContact?.numberLabel == contact.numberLabel &&
                lastLoadedAvatarUri == avatar &&
                lastLoadedCallHandle == currentHandle) {
                return@getCallContact
            }

            callContact = contact
            lastLoadedCallHandle = currentHandle
            lastLoadedAvatarUri = avatar

            runOnUiThread {
                updateOtherPersonsInfo(contact, avatar)
                checkCalledSIMCard()
            }
        }
    }

    private fun acceptCall() {
        CallManager.accept()
    }

    private fun initOutgoingCallUI() {
        enableProximitySensor()
        binding.incomingCallHolder.beGone()
        binding.ongoingCallHolder.beVisible()
        binding.callEnd.beVisible()
    }

    // ── Animation helpers ──────────────────────────────────────────────────

    /**
     * Shows the call-waiting banner with a slide-up + fade-in animation.
     * Safe to call repeatedly — animation is skipped if already visible.
     */
    private fun showCallWaitingBanner() {
        val banner = binding.callWaitingHolder
        if (banner.visibility == android.view.View.VISIBLE) return
        banner.clearAnimation()
        val anim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.slide_up_incoming)
        banner.startAnimation(anim)
        banner.beVisible()
    }

    /**
     * Hides the call-waiting banner with a fade-out animation.
     * Safe to call repeatedly — no-op if already gone.
     */
    private fun hideCallWaitingBanner() {
        val banner = binding.callWaitingHolder
        if (banner.visibility != android.view.View.VISIBLE) return
        val anim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_out_call)
        anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(a: android.view.animation.Animation) {}
            override fun onAnimationRepeat(a: android.view.animation.Animation) {}
            override fun onAnimationEnd(a: android.view.animation.Animation) { banner.beGone() }
        })
        banner.startAnimation(anim)
    }

    /**
     * Animates the alpha of [active_call_dim_overlay] between 0 (undimmed) and 1 (dimmed).
     * Duration: 200ms. This scrim sits over the avatar / name / status labels and
     * visually de-emphasises the active call while the incoming call banner is visible.
     */
    private fun animateCallWaitingDim(dim: Boolean) {
        val overlay = binding.root.findViewById<android.view.View>(R.id.active_call_dim_overlay)
            ?: return
        val targetAlpha = if (dim) 1f else 0f
        if (overlay.alpha == targetAlpha) return
        ObjectAnimator.ofFloat(overlay, "alpha", overlay.alpha, targetAlpha).apply {
            duration = 200
            start()
        }
    }

    private fun callRinging() {
        // Only show the full-screen incoming call UI when there is NO active or held call.
        // During call-waiting (active call + second ringing call), updateState() handles
        // the incoming banner overlay; we must NOT replace the active-call UI here.
        val hasLiveCall = CallManager.getActiveCall() != null || CallManager.getHeldCall() != null
        if (!hasLiveCall) {
            binding.incomingCallHolder.beVisible()
        }
    }

    private fun callStarted() {
        enableProximitySensor()
        binding.incomingCallHolder.beGone()
        binding.ongoingCallHolder.beVisible()
        binding.callEnd.beVisible()

        // Clear call-waiting banner/tone only if no ringing call exists anymore.
        // If a second call is currently ringing while the active call is updated,
        // do NOT hide the banner or stop the waiting tone.
        if (CallManager.getRingingCall() == null) {
            callAudioManager.stopCallWaitingTone()
            hideCallWaitingBanner()
            animateCallWaitingDim(dim = false)
        }

        callDurationHandler.removeCallbacks(updateCallDurationTask)
        callDurationHandler.post(updateCallDurationTask)
    }

    private fun showPhoneAccountPicker() {
        if (callContact != null) {
            getHandleToUse(intent, callContact!!.number) { handle ->
                CallManager.getPrimaryCall()?.phoneAccountSelected(handle, false)
            }
        }
    }

    private fun endCall() {
        if (CallManager.getPhoneState() != NoCall) {
            CallManager.reject()
        }
        disableProximitySensor()
        audioRouteChooserDialog?.dismissAllowingStateLoss()

        if (isCallEnded) {
            if (CallManager.getPhoneState() == NoCall) {
                safeFinishAndRemoveTask()
            }
            return
        }

        isCallEnded = true
        if (CallManager.getPhoneState() == NoCall) {
            safeFinishAndRemoveTask()
        }
    }

    private fun safeFinishAndRemoveTask() {
        try {
            if (intent != null) {
                finishAndRemoveTask()
            } else {
                finish()
            }
        } catch (_: Exception) {
            finish()
        }
    }

    private val callCallback = object : CallManagerListener {
        override fun onStateChanged() {
            updateState()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) {
            updateCallAudioState(audioState)
        }

        override fun onPrimaryCallChanged(call: Call) {
            callDurationHandler.removeCallbacks(updateCallDurationTask)
            updateCallContactInfo(call)
            updateState()
        }

        override fun onSecondCallArrived(call: Call) {
            // The ViewModel's secondCallEvent handles the tone via StateFlow collection.
            // Here we just ensure the UI updates synchronously for the incoming banner.
            updateState()
        }

        override fun onRingingCallEnded() {
            // The ringing call was removed (answered, rejected, or timed out).
            // Stop the waiting tone and hide the call-waiting banner immediately,
            // covering paths where the call was dismissed outside the UI buttons
            // (e.g. via notification action, headset button, or remote hangup).
            callAudioManager.stopCallWaitingTone()
            runOnUiThread {
                hideCallWaitingBanner()
                animateCallWaitingDim(dim = false)
            }
        }
    }

    private val updateCallDurationTask = object : Runnable {
        override fun run() {
            callDuration = CallManager.getPrimaryCall().getCallDuration()
            if (!isCallEnded) {
                binding.callStatusLabel.text = callDuration.getFormattedDuration()
                callDurationHandler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("NewApi")
    private fun addLockScreenFlags() {
        if (isOreoMr1Plus()) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        if (isOreoPlus()) {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenOnWakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "com.novadial.phone:full_wake_lock")
            screenOnWakeLock!!.acquire(5 * 1000L)
        } catch (e: Exception) {
        }
    }

    private fun enableProximitySensor() {
        if (!config.disableProximitySensor && (proximityWakeLock == null || proximityWakeLock?.isHeld == false)) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            proximityWakeLock = powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "com.novadial.phone:wake_lock")
            proximityWakeLock!!.acquire(60 * MINUTE_SECONDS * 1000L)
        }
    }

    private fun disableProximitySensor() {
        if (proximityWakeLock?.isHeld == true) {
            proximityWakeLock!!.release()
        }
    }

    private fun disableAllActionButtons() {
        (binding.ongoingCallHolder.children + binding.callEnd)
            .filter { it is ImageView && it.isVisible() }
            .forEach { view ->
                setActionButtonEnabled(button = view as ImageView, enabled = false)
            }
    }

    private fun setActionButtonEnabled(button: ImageView, enabled: Boolean) {
        button.apply {
            isEnabled = enabled
            alpha = if (enabled) 1.0f else LOWER_ALPHA
        }
    }

    private fun getActiveButtonColor() = getNovaAccentColor()
    private fun getInactiveButtonColor() = getProperTextColor().adjustAlpha(0.10f)

    private fun toggleButtonColor(view: ImageView, enabled: Boolean) {
        if (enabled) {
            val color = getActiveButtonColor()
            view.background.applyColorFilter(color)
            view.applyColorFilter(color.getContrastColor())
        } else {
            view.background.applyColorFilter(getInactiveButtonColor())
            view.applyColorFilter(getProperBackgroundColor().getContrastColor())
        }
    }

    private fun clearChar(view: View) {
        binding.dialpadInput.dispatchKeyEvent(binding.dialpadInput.getKeyEvent(KeyEvent.KEYCODE_DEL))
    }

    private fun clearInput(): Boolean {
        binding.dialpadInput.setText("")
        return true
    }

    // ── Floating Button ────────────────────────────────────────────────────

    private fun startFloatingButton(phoneNumber: String? = null) {
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, FloatingButtonService::class.java)
            intent.putExtra("phone_number", phoneNumber)
            startService(intent)
        } else {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } catch (e: Exception) {
            }
        }
    }

    private fun stopFloatingButton() {
        stopService(Intent(this, FloatingButtonService::class.java))
    }
}
