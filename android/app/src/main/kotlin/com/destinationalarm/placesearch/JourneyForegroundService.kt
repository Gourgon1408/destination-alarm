package com.destinationalarm.placesearch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import java.util.concurrent.Executors

class JourneyForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.destinationalarm.placesearch.START"
        const val ACTION_STOP = "com.destinationalarm.placesearch.STOP"
        const val ACTION_DISMISS = "com.destinationalarm.placesearch.DISMISS"
        const val ACTION_TEST_ALARM = "com.destinationalarm.placesearch.TEST_ALARM"
        const val ACTION_STOP_TEST_ALARM = "com.destinationalarm.placesearch.STOP_TEST_ALARM"
        const val ACTION_SERVICE_EVENT = "com.destinationalarm.placesearch.SERVICE_EVENT"

        private const val TAG = "DAS_ALARM"
        private const val CHANNEL_STATUS = "journey_status"
        private const val CHANNEL_ARRIVAL = "arrival_alarm"
        private const val NOTIFICATION_ID = 4201
        private const val PREFS = "destination_alarm_native_journey"
        private const val LOCATION_UPDATE_INTERVAL_MS = 5_000L
        private const val LOCATION_MIN_UPDATE_INTERVAL_MS = 2_500L
        private const val LOCATION_STALE_AFTER_MS = 30_000L

        @Volatile
        private var runningInstance: JourneyForegroundService? = null

        fun status(context: Context): Map<String, Any?> =
            runningInstance?.snapshot() ?: savedStatus(context.applicationContext)

        fun stop(context: Context) {
            val service = runningInstance
            if (service != null) {
                service.stopJourney(clearJourney = true)
            } else {
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().clear().apply()
                sendEvent(context.applicationContext, savedStatus(context.applicationContext))
            }
        }

        fun dismiss(context: Context) {
            val service = runningInstance
            if (service != null) {
                service.dismissArrival()
            } else {
                sendEvent(context.applicationContext, savedStatus(context.applicationContext))
            }
        }

        fun stopTest(context: Context) {
            val service = runningInstance
            if (service != null) service.stopTestAlarm()
        }

        fun acknowledgeUserStop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("userStopped", false).apply()
        }

        private fun savedStatus(context: Context): Map<String, Any?> {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val saved = prefs.getBoolean("journeySaved", false)
            val arrived = prefs.getBoolean("arrived", false)
            val currentLat = prefs.getString("currentLatitude", null)?.toDoubleOrNull() ?: Double.NaN
            val currentLon = prefs.getString("currentLongitude", null)?.toDoubleOrNull() ?: Double.NaN
            val hasCurrent = currentLat.isFinite() && currentLon.isFinite()
            val destinationLat = prefs.getString("destinationLatitude", null)?.toDoubleOrNull() ?: Double.NaN
            val destinationLon = prefs.getString("destinationLongitude", null)?.toDoubleOrNull() ?: Double.NaN
            val distance = if (hasCurrent && destinationLat.isFinite() && destinationLon.isFinite()) {
                ArrivalRules.distanceMetres(currentLat, currentLon, destinationLat, destinationLon)
            } else null
            return mapOf(
                "serviceRunning" to false,
                "tracking" to false,
                "arrived" to arrived,
                "alarmSounding" to false,
                "testAlarmActive" to false,
                "journeySaved" to saved,
                "userStopped" to prefs.getBoolean("userStopped", false),
                "destinationLatitude" to if (saved) destinationLat else null,
                "destinationLongitude" to if (saved) destinationLon else null,
                "radiusMetres" to if (saved) prefs.getString("radiusMetres", "2000")?.toDoubleOrNull() else null,
                "currentLatitude" to if (hasCurrent) currentLat else null,
                "currentLongitude" to if (hasCurrent) currentLon else null,
                "remainingMetres" to distance,
                "error" to prefs.getString("lastError", null),
            )
        }

        private fun sendEvent(context: Context, state: Map<String, Any?>) {
            Log.i(TAG, "event=state_broadcast arrived=${state["arrived"]} tracking=${state["tracking"]} alarmSounding=${state["alarmSounding"]} testAlarmActive=${state["testAlarmActive"]} error=${state["error"]}")
            val intent = Intent(ACTION_SERVICE_EVENT).setPackage(context.packageName)
            state.forEach { (key, value) ->
                when (value) {
                    is Boolean -> intent.putExtra(key, value)
                    is Double -> intent.putExtra(key, value)
                    is String -> intent.putExtra(key, value)
                    null -> Unit
                }
            }
            context.sendBroadcast(intent)
        }
    }

    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val fusedLocation by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var destinationLatitude = 0.0
    private var destinationLongitude = 0.0
    private var radiusMetres = 2_000.0
    private var currentLatitude: Double? = null
    private var currentLongitude: Double? = null
    private var journeySaved = false
    private var tracking = false
    private var alarmSounding = false
    private var arrivalEventPending = false
    private var locationCallback: LocationCallback? = null
    private var arrivalGate: ArrivalGate? = null
    private val alarmLifecycle = AlarmLifecycle()
    private val arrived: Boolean
        get() = alarmLifecycle.arrived
    private val testAlarmActive: Boolean
        get() = alarmLifecycle.playbackMode == AlarmPlaybackMode.TEST
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: Any? = null
    private var playbackPurpose: String? = null
    private var lastLocationElapsedMillis: Long? = null
    private var lastFixCallbackElapsedRealtimeMs: Long? = null
    private var lastLocationFixElapsedRealtimeMs: Long? = null
    private var lastArrivalConfirmedElapsedRealtimeMs: Long? = null
    private var playbackRequestedElapsedRealtimeMs: Long? = null
    private val locationCallbackExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "DAS-location-callback").apply { isDaemon = true }
    }
    private var locationCallbackGeneration = 0L
    private var locationRemovalTask: Task<Void>? = null
    private var lastError: String? = null
    private var stopping = false
    private val handler = android.os.Handler(Looper.getMainLooper())
    private val locationWatchdog = object : Runnable {
        override fun run() {
            if (!tracking) return
            val ageMillis = lastLocationElapsedMillis?.let { SystemClock.elapsedRealtime() - it }
            if (ageMillis == null || ageMillis > LOCATION_STALE_AFTER_MS) {
                lastError = "No fresh GPS update received. Check location services and GPS signal."
                Log.w(TAG, "event=location_watchdog ageMs=${ageMillis ?: "none"} tracking=$tracking radiusMetres=$radiusMetres confirmations=${arrivalGate?.confirmationCount ?: 0}")
                updateStatusNotification(lastError!!)
                emitState()
            }
            handler.postDelayed(this, LOCATION_STALE_AFTER_MS)
        }
    }
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.i(TAG, "event=audio_focus_change focusChange=$focusChange purpose=$playbackPurpose")
        val player = mediaPlayer
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> if (playbackPurpose != null && player != null) {
                try {
                    if (!player.isPlaying) player.start()
                    alarmSounding = player.isPlaying
                    lastError = null
                    Log.i(TAG, "event=audio_resumed focusChange=$focusChange isPlaying=$alarmSounding")
                    emitState()
                } catch (error: RuntimeException) {
                    reportAudioFailure("resume", error)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (player != null) {
                try {
                    if (player.isPlaying) player.pause()
                    alarmSounding = false
                    lastError = "Alarm playback paused because another app took audio focus. It will resume when audio focus returns."
                    Log.w(TAG, "event=audio_paused focusChange=$focusChange")
                    emitState()
                } catch (error: RuntimeException) {
                    reportAudioFailure("pause_after_focus_loss", error)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        restoreJourney()
        createNotificationChannels()
        Log.i(TAG, "event=service_created serviceRunning=true arrived=$arrived testAlarmActive=$testAlarmActive")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "event=service_command action=${intent?.action ?: "null"} startId=$startId")
        when (intent?.action) {
            ACTION_START -> startJourney(intent)
            ACTION_STOP -> stopJourney(clearJourney = true)
            ACTION_DISMISS -> dismissArrival()
            ACTION_TEST_ALARM -> startTestAlarm()
            ACTION_STOP_TEST_ALARM -> stopTestAlarm()
            else -> {
                lastError = "Journey service received an unsupported action."
                emitState()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startJourney(intent: Intent) {
        if (tracking) {
            emitState()
            return
        }
        val lat = intent.getDoubleExtra("latitude", Double.NaN)
        val lon = intent.getDoubleExtra("longitude", Double.NaN)
        val radius = intent.getDoubleExtra("radiusMetres", Double.NaN)
        if (!lat.isFinite() || lat !in -90.0..90.0 || !lon.isFinite() || lon !in -180.0..180.0 || radius !in setOf(500.0, 1_000.0, 2_000.0, 5_000.0)) {
            failStart("Destination or alert radius is invalid.")
            return
        }
        destinationLatitude = lat
        destinationLongitude = lon
        radiusMetres = radius
        alarmLifecycle.beginJourney()
        arrivalGate = ArrivalGate(radiusMetres)
        alarmSounding = false
        journeySaved = true
        currentLatitude = null
        currentLongitude = null
        lastLocationElapsedMillis = null
        lastFixCallbackElapsedRealtimeMs = null
        lastLocationFixElapsedRealtimeMs = null
        lastError = null
        val callbackGeneration = ++locationCallbackGeneration
        Log.i(TAG, "event=journey_start radiusMetres=$radiusMetres elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
        saveJourney()
        prefs.edit().putBoolean("userStopped", false).apply()

        val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!hasLocationPermission) {
            failStart("Location permission was denied. Allow location while using the app, then try again.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !manager.isLocationEnabled) {
            failStart("Location services are off. Turn on GPS/location and try again.")
            return
        }

        try {
            promoteToForeground(statusNotification("Starting location monitoring…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (error: SecurityException) {
            failStart("Android denied foreground location service startup. Check location permission and GPS.")
            return
        } catch (error: RuntimeException) {
            failStart("Could not start the location service: ${error.message ?: "service unavailable"}")
            return
        }

        var previousCallbackReceiptElapsedRealtimeMs: Long? = null
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val callbackReceiptElapsedRealtimeMs = SystemClock.elapsedRealtime()
                val callbackIntervalMs = previousCallbackReceiptElapsedRealtimeMs?.let {
                    callbackReceiptElapsedRealtimeMs - it
                }
                previousCallbackReceiptElapsedRealtimeMs = callbackReceiptElapsedRealtimeMs
                val locations = result.locations.map { Location(it) }.sortedBy { it.elapsedRealtimeNanos }
                Log.i(TAG, "event=location_callback_received callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs callbackIntervalMs=$callbackIntervalMs thread=${Thread.currentThread().name} batchCount=${locations.size}")
                val posted = handler.post {
                    processLocationResult(
                        locations = locations,
                        callbackReceiptElapsedRealtimeMs = callbackReceiptElapsedRealtimeMs,
                        callbackIntervalMs = callbackIntervalMs,
                        callbackGeneration = callbackGeneration,
                    )
                }
                if (!posted) Log.e(TAG, "event=location_callback_dispatch_failed callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs reason=main_handler_rejected")
            }

            override fun onLocationAvailability(availability: com.google.android.gms.location.LocationAvailability) {
                val callbackReceiptElapsedRealtimeMs = SystemClock.elapsedRealtime()
                val isAvailable = availability.isLocationAvailable
                Log.i(TAG, "event=location_availability_callback_received callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs available=$isAvailable thread=${Thread.currentThread().name}")
                val posted = handler.post {
                    val processingElapsedRealtimeMs = SystemClock.elapsedRealtime()
                    val callbackQueueDelayMs = processingElapsedRealtimeMs - callbackReceiptElapsedRealtimeMs
                    if (callbackGeneration != locationCallbackGeneration) {
                        Log.i(TAG, "event=location_availability_callback_ignored callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs callbackQueueDelayMs=$callbackQueueDelayMs reason=subscription_replaced")
                        return@post
                    }
                    lastError = if (isAvailable) null else
                        "Location signal is unavailable. Check GPS/location settings and your surroundings."
                    Log.i(TAG, "event=location_availability available=$isAvailable callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs processingElapsedRealtimeMs=$processingElapsedRealtimeMs callbackQueueDelayMs=$callbackQueueDelayMs tracking=$tracking confirmationCount=${arrivalGate?.confirmationCount ?: 0} confirmationReset=false error=$lastError")
                    if (!isAvailable) updateStatusNotification(lastError)
                    emitState()
                }
                if (!posted) Log.e(TAG, "event=location_availability_dispatch_failed callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs reason=main_handler_rejected")
            }
        }
        locationCallback = callback
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .setMinUpdateIntervalMillis(LOCATION_MIN_UPDATE_INTERVAL_MS)
            .setMaxUpdateDelayMillis(0L)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            fusedLocation.requestLocationUpdates(request, locationCallbackExecutor, callback)
                .addOnSuccessListener {
                    if (callbackGeneration != locationCallbackGeneration || arrived || !journeySaved) {
                        Log.i(TAG, "event=location_subscription_activation_ignored generation=$callbackGeneration currentGeneration=$locationCallbackGeneration arrived=$arrived journeySaved=$journeySaved")
                        return@addOnSuccessListener
                    }
                    tracking = true
                    lastLocationElapsedMillis = SystemClock.elapsedRealtime()
                    handler.removeCallbacks(locationWatchdog)
                    handler.postDelayed(locationWatchdog, LOCATION_STALE_AFTER_MS)
                    Log.i(TAG, "event=location_subscription_active intervalMs=$LOCATION_UPDATE_INTERVAL_MS minUpdateIntervalMs=$LOCATION_MIN_UPDATE_INTERVAL_MS minUpdateDistanceMetres=0 maxUpdateDelayMs=0 radiusMetres=$radiusMetres elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
                    updateStatusNotification()
                    emitState()
                }
                .addOnFailureListener { error ->
                    Log.e(TAG, "event=location_subscription_failed", error)
                    failStart("Could not subscribe to location updates: ${error.message ?: "location unavailable"}")
                }
        } catch (error: SecurityException) {
            failStart("Location permission was revoked. Allow location while using the app and retry.")
        } catch (error: RuntimeException) {
            failStart("Could not subscribe to location updates: ${error.message ?: "location unavailable"}")
        }
    }

    private fun processLocationResult(
        locations: List<Location>,
        callbackReceiptElapsedRealtimeMs: Long,
        callbackIntervalMs: Long?,
        callbackGeneration: Long,
    ) {
        val processingElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val processingStartedElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        val callbackQueueDelayMs = processingElapsedRealtimeMs - callbackReceiptElapsedRealtimeMs
        Log.i(TAG, "event=location_callback_processing_started callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs processingElapsedRealtimeMs=$processingElapsedRealtimeMs callbackQueueDelayMs=$callbackQueueDelayMs callbackIntervalMs=$callbackIntervalMs batchCount=${locations.size} thread=${Thread.currentThread().name}")
        try {
            if (callbackGeneration != locationCallbackGeneration) {
                Log.i(TAG, "event=location_callback_processing_ignored callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs callbackQueueDelayMs=$callbackQueueDelayMs reason=subscription_replaced")
                return
            }
            if (locations.isEmpty()) {
                lastError = "GPS returned an empty location update. Waiting for a fresh fix."
                Log.w(TAG, "event=gps_update_empty callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs processingElapsedRealtimeMs=$processingElapsedRealtimeMs callbackQueueDelayMs=$callbackQueueDelayMs radiusMetres=$radiusMetres confirmationCount=${arrivalGate?.confirmationCount ?: 0} confirmationReset=false reason=missing_location_ignored")
                updateStatusNotification(lastError)
                emitState()
                return
            }
            Log.i(TAG, "event=location_batch_received count=${locations.size} callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs processingElapsedRealtimeMs=$processingElapsedRealtimeMs callbackQueueDelayMs=$callbackQueueDelayMs")
            for (location in locations) {
                if (arrived) break
                processLocationFix(location, callbackReceiptElapsedRealtimeMs, SystemClock.elapsedRealtime())
            }
        } finally {
            val processingDurationNanos = SystemClock.elapsedRealtimeNanos() - processingStartedElapsedRealtimeNanos
            Log.i(TAG, "event=location_callback_processing_complete callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs callbackQueueDelayMs=$callbackQueueDelayMs processingDurationMs=${processingDurationNanos / 1_000_000.0} callbackIntervalMs=$callbackIntervalMs thread=${Thread.currentThread().name}")
        }
    }

    private fun processLocationFix(
        location: Location,
        callbackReceiptElapsedRealtimeMs: Long,
        processingElapsedRealtimeMs: Long,
    ) {
        val fixElapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000L
        val ageAtCallbackMs = callbackReceiptElapsedRealtimeMs - fixElapsedRealtimeMs
        val ageAtProcessingMs = processingElapsedRealtimeMs - fixElapsedRealtimeMs
        val accuracyMetres = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN
        val invalidFixReason = ArrivalRules.invalidFixReason(
            location.latitude,
            location.longitude,
            ageAtProcessingMs,
            LOCATION_STALE_AFTER_MS,
        )
        if (invalidFixReason != null) {
            val confirmationsBefore = arrivalGate?.confirmationCount ?: 0
            val reset = arrivalGate?.resetConfirmation(invalidFixReason)
            lastError = if (invalidFixReason == "stale_fix") {
                "GPS fix is stale. Waiting for a fresh location update."
            } else {
                "GPS returned invalid coordinates. Waiting for a valid location fix."
            }
            Log.w(TAG, "event=gps_fix_rejected fixTimestampEpochMs=${location.time} fixElapsedRealtimeMs=$fixElapsedRealtimeMs callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs processingElapsedRealtimeMs=$processingElapsedRealtimeMs callbackQueueDelayMs=${processingElapsedRealtimeMs - callbackReceiptElapsedRealtimeMs} ageAtCallbackMs=$ageAtCallbackMs ageAtProcessingMs=$ageAtProcessingMs accuracyMetres=$accuracyMetres radiusMetres=$radiusMetres confirmationCountBefore=$confirmationsBefore confirmationCountAfter=${reset?.confirmationCount ?: 0} reason=${reset?.reason ?: invalidFixReason}")
            updateStatusNotification(lastError)
            emitState()
            return
        }

        val previousFixElapsedRealtimeMs = lastLocationFixElapsedRealtimeMs
        if (previousFixElapsedRealtimeMs != null && fixElapsedRealtimeMs <= previousFixElapsedRealtimeMs) {
            Log.i(TAG, "event=gps_fix_ignored fixTimestampEpochMs=${location.time} fixElapsedRealtimeMs=$fixElapsedRealtimeMs previousFixElapsedRealtimeMs=$previousFixElapsedRealtimeMs callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs processingElapsedRealtimeMs=$processingElapsedRealtimeMs callbackQueueDelayMs=${processingElapsedRealtimeMs - callbackReceiptElapsedRealtimeMs} ageAtCallbackMs=$ageAtCallbackMs ageAtProcessingMs=$ageAtProcessingMs confirmationCount=${arrivalGate?.confirmationCount ?: 0} confirmationReset=false reason=duplicate_or_out_of_order_fix")
            return
        }

        lastLocationFixElapsedRealtimeMs = fixElapsedRealtimeMs
        lastError = null
        lastLocationElapsedMillis = callbackReceiptElapsedRealtimeMs
        val previousFixCallbackElapsedRealtimeMs = lastFixCallbackElapsedRealtimeMs
        lastFixCallbackElapsedRealtimeMs = callbackReceiptElapsedRealtimeMs
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        saveJourney()
        val distance = ArrivalRules.distanceMetres(
            location.latitude,
            location.longitude,
            destinationLatitude,
            destinationLongitude,
        )
        val confirmationsBefore = arrivalGate?.confirmationCount ?: 0
        val gateCheck = arrivalGate?.update(distance, accuracyMetres)
        val fixIntervalMillis = previousFixElapsedRealtimeMs?.let { fixElapsedRealtimeMs - it }
        Log.i(TAG, "event=gps_fix fixTimestampEpochMs=${location.time} fixElapsedRealtimeMs=$fixElapsedRealtimeMs callbackReceiptElapsedRealtimeMs=$callbackReceiptElapsedRealtimeMs processingElapsedRealtimeMs=$processingElapsedRealtimeMs callbackQueueDelayMs=${processingElapsedRealtimeMs - callbackReceiptElapsedRealtimeMs} ageAtCallbackMs=$ageAtCallbackMs ageAtProcessingMs=$ageAtProcessingMs fixIntervalSincePreviousMs=$fixIntervalMillis callbackIntervalSincePreviousMs=${previousFixCallbackElapsedRealtimeMs?.let { callbackReceiptElapsedRealtimeMs - it }} accuracyMetres=$accuracyMetres accuracyLimitMetres=${ArrivalRules.maximumAccuracyMetres(radiusMetres)} distanceMetres=$distance radiusMetres=$radiusMetres confirmationCountBefore=$confirmationsBefore confirmationCountAfter=${gateCheck?.confirmationCount ?: 0} arrivalConfirmed=${gateCheck?.confirmed ?: false} check=${gateCheck?.reason ?: "gate_missing"}")
        lastError = when (gateCheck?.reason) {
            "inaccurate_fix_ignored" -> "GPS accuracy is temporarily limited. Keeping arrival confirmation while waiting for a clearer fix."
            "accuracy_unavailable_ignored" -> "GPS accuracy is unavailable for this fix. Keeping arrival confirmation while waiting for a measured fix."
            else -> null
        }
        if (gateCheck?.confirmed == true) {
            if (alarmLifecycle.confirmArrival()) onArrival()
            else Log.w(TAG, "event=duplicate_arrival_confirmation_ignored elapsedRealtimeMs=${SystemClock.elapsedRealtime()} distanceMetres=$distance radiusMetres=$radiusMetres")
        } else {
            updateStatusNotification(lastError)
            emitState(distance)
        }
    }

    private fun onArrival() {
        tracking = false
        arrivalEventPending = true
        prefs.edit().putBoolean("arrived", true).apply()
        val confirmedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        lastArrivalConfirmedElapsedRealtimeMs = confirmedElapsedRealtimeMs
        handler.removeCallbacks(locationWatchdog)
        removeLocationUpdates()
        val confirmProcessingDelayMs = lastFixCallbackElapsedRealtimeMs?.let { confirmedElapsedRealtimeMs - it }
        Log.i(TAG, "event=arrival_confirmed elapsedRealtimeMs=$confirmedElapsedRealtimeMs wallClockEpochMs=${System.currentTimeMillis()} confirmationProcessingDelayMs=$confirmProcessingDelayMs radiusMetres=$radiusMetres")
        try {
            promoteToForeground(
                arrivalNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            Log.i(TAG, "event=foreground_state_changed type=mediaPlayback arrived=true elapsedRealtimeMs=${SystemClock.elapsedRealtime()} transitionDelayMs=${SystemClock.elapsedRealtime() - confirmedElapsedRealtimeMs}")
            Log.i(TAG, "event=arrival_notification_posted fallback=false channel=$CHANNEL_ARRIVAL elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
        } catch (error: RuntimeException) {
            reportAlarmFailure("foreground_transition", error)
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, arrivalNotification())
                Log.i(TAG, "event=arrival_notification_posted fallback=true")
            } catch (notificationError: RuntimeException) {
                Log.e(TAG, "event=arrival_notification_failed", notificationError)
                lastError = "Arrival was detected, but Android could not show its notification: ${notificationError.message ?: "notification unavailable"}"
            }
        }
        startAlarmAudio("arrival")
        emitState()
    }

    private fun startAlarmAudio(purpose: String) {
        releaseAlarmAudio()
        playbackPurpose = purpose
        val requestedElapsedRealtimeMs = SystemClock.elapsedRealtime()
        playbackRequestedElapsedRealtimeMs = requestedElapsedRealtimeMs
        val delayAfterArrivalMs = lastArrivalConfirmedElapsedRealtimeMs?.let { requestedElapsedRealtimeMs - it }
        Log.i(TAG, "event=audio_playback_requested purpose=$purpose requestedElapsedRealtimeMs=$requestedElapsedRealtimeMs delayAfterArrivalMs=$delayAfterArrivalMs")
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            val focusGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(audioFocusListener, handler)
                    .setWillPauseWhenDucked(true)
                    .build()
                audioFocusRequest = request
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(audioFocusListener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            Log.i(TAG, "event=audio_focus_requested granted=$focusGranted purpose=$purpose stream=alarm")
            if (!focusGranted) throw IllegalStateException("Android did not grant alarm audio focus.")

            val player = MediaPlayer()
            mediaPlayer = player
            player.setAudioAttributes(audioAttributes)
            player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            player.isLooping = true
            player.setVolume(1.0f, 1.0f)
            player.setOnPreparedListener { prepared ->
                val stillRequested = playbackPurpose == purpose &&
                    ((purpose == "arrival" && arrived) || (purpose == "test" && testAlarmActive))
                if (!stillRequested) {
                    Log.i(TAG, "event=audio_prepared_cancelled purpose=$purpose")
                    releaseAlarmAudio()
                    return@setOnPreparedListener
                }
                try {
                    prepared.start()
                    alarmSounding = prepared.isPlaying
                    lastError = if (alarmSounding) null else "The alarm player prepared but did not start playback."
                    val startedElapsedRealtimeMs = SystemClock.elapsedRealtime()
                    val preparationDelayMs = playbackRequestedElapsedRealtimeMs?.let { startedElapsedRealtimeMs - it }
                    val delayAfterArrivalMs = lastArrivalConfirmedElapsedRealtimeMs?.let { startedElapsedRealtimeMs - it }
                    Log.i(TAG, "event=audio_playback_started purpose=$purpose isPlaying=$alarmSounding looping=${prepared.isLooping} volume=1.0 startedElapsedRealtimeMs=$startedElapsedRealtimeMs preparationDelayMs=$preparationDelayMs delayAfterArrivalMs=$delayAfterArrivalMs")
                    emitState()
                } catch (error: RuntimeException) {
                    reportAudioFailure("playback_start", error)
                }
            }
            player.setOnErrorListener { _, what, extra ->
                reportAudioFailure("media_player", IllegalStateException("MediaPlayer error what=$what extra=$extra"))
                true
            }
            val descriptor = resources.openRawResourceFd(R.raw.das_arrival_alarm)
            val soundBytes = descriptor.length
            descriptor.use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            Log.i(TAG, "event=audio_initialization_started purpose=$purpose resource=das_arrival_alarm.wav bytes=$soundBytes looping=true audioUsage=alarm contentType=sonification elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
            player.prepareAsync()
        } catch (error: Exception) {
            reportAudioFailure("initialization", error)
        }
    }

    private fun releaseAlarmAudio() {
        val player = mediaPlayer
        mediaPlayer = null
        Log.i(TAG, "event=audio_release_requested purpose=$playbackPurpose wasPlaying=${runCatching { player?.isPlaying == true }.getOrDefault(false)}")
        try {
            if (player != null) {
                try {
                    if (player.isPlaying) player.stop()
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "event=audio_stop_state_warning", error)
                }
                player.release()
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "event=audio_release_failed", error)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it as AudioFocusRequest) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "event=audio_focus_release_failed", error)
        }
        playbackPurpose = null
        playbackRequestedElapsedRealtimeMs = null
        alarmSounding = false
    }

    private fun reportAudioFailure(stage: String, error: Exception) {
        Log.e(TAG, "event=audio_playback_failed stage=$stage purpose=$playbackPurpose", error)
        lastError = "Alarm audio failed during $stage: ${error.message ?: error.javaClass.simpleName}"
        releaseAlarmAudio()
        emitState()
    }

    private fun reportAlarmFailure(stage: String, error: Exception) {
        Log.e(TAG, "event=alarm_operation_failed stage=$stage", error)
        lastError = "Arrival alarm failed during $stage: ${error.message ?: error.javaClass.simpleName}"
        emitState()
    }

    private fun startTestAlarm() {
        if (!alarmLifecycle.beginTest()) {
            Log.w(TAG, "event=test_alarm_rejected playbackMode=${alarmLifecycle.playbackMode} arrived=$arrived")
            lastError = "Dismiss the active arrival alarm before starting a test alarm."
            emitState()
            return
        }
        lastError = null
        try {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                if (tracking) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            promoteToForeground(testAlarmNotification(), types)
            Log.i(TAG, "event=foreground_state_changed type=$types testAlarmActive=true tracking=$tracking")
            startAlarmAudio("test")
            emitState()
        } catch (error: RuntimeException) {
            alarmLifecycle.finishTest()
            Log.e(TAG, "event=test_alarm_start_failed", error)
            lastError = "Could not start the alarm test: ${error.message ?: "foreground service unavailable"}"
            if (!tracking) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopping = true
                stopSelf()
            }
            emitState()
        }
    }

    private fun stopTestAlarm() {
        if (!alarmLifecycle.finishTest()) {
            Log.i(TAG, "event=test_alarm_stop_ignored playbackMode=${alarmLifecycle.playbackMode}")
            return
        }
        Log.i(TAG, "event=test_alarm_stopped tracking=$tracking journeySaved=$journeySaved arrived=$arrived")
        releaseAlarmAudio()
        lastError = null
        if (tracking) {
            try {
                promoteToForeground(statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                updateStatusNotification()
                emitState()
            } catch (error: RuntimeException) {
                failStart("Could not restore journey tracking after the alarm test: ${error.message ?: "foreground service unavailable"}")
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopping = true
            emitState()
            stopSelf()
        }
    }

    private fun dismissArrival() {
        if (!alarmLifecycle.dismissArrival()) {
            Log.w(TAG, "event=alarm_dismiss_ignored arrived=$arrived playbackMode=${alarmLifecycle.playbackMode}")
            return
        }
        Log.i(TAG, "event=alarm_dismissed tracking=$tracking journeySaved=$journeySaved")
        releaseAlarmAudio()
        lastError = null
        handler.removeCallbacks(locationWatchdog)
        removeLocationUpdates()
        tracking = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopping = true
        emitState()
        stopSelf()
    }

    private fun stopJourney(clearJourney: Boolean) {
        Log.i(TAG, "event=journey_stop clearJourney=$clearJourney arrived=$arrived testAlarmActive=$testAlarmActive")
        releaseAlarmAudio()
        handler.removeCallbacks(locationWatchdog)
        removeLocationUpdates()
        tracking = false
        if (clearJourney) {
            journeySaved = false
            alarmLifecycle.clearJourney()
            currentLatitude = null
            currentLongitude = null
            lastError = null
            prefs.edit().clear().putBoolean("userStopped", true).apply()
        } else {
            persistState()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopping = true
        emitState()
        stopSelf()
    }

    private fun failStart(message: String) {
        lastError = message
        Log.e(TAG, "event=journey_start_failed message=$message")
        tracking = false
        releaseAlarmAudio()
        handler.removeCallbacks(locationWatchdog)
        removeLocationUpdates()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopping = true
        emitState()
        stopSelf()
    }

    private fun removeLocationUpdates() {
        locationCallbackGeneration++
        val callback = locationCallback ?: return
        locationRemovalTask = fusedLocation.removeLocationUpdates(callback)
            .addOnFailureListener { error -> Log.e(TAG, "event=location_unsubscribe_failed", error) }
        locationCallback = null
        Log.i(TAG, "event=location_subscription_removed")
    }

    private fun promoteToForeground(notification: Notification, type: Int) {
        Log.i(TAG, "event=foreground_promotion_requested type=$type sdk=${Build.VERSION.SDK_INT}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Journey monitoring", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows when a destination journey is being monitored."
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ARRIVAL, "Destination arrival alarm", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows when you enter your selected destination radius."
                setSound(null, null)
                enableVibration(true)
            },
        )
    }

    private fun statusNotification(message: String = "Monitoring your destination") =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Destination Alarm Search is active")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())
            .addAction(0, "Stop journey", serviceAction(ACTION_STOP, 2))
            .build()

    private fun arrivalNotification() = NotificationCompat.Builder(this, CHANNEL_ARRIVAL)
        .setSmallIcon(android.R.drawable.ic_dialog_map)
        .setContentTitle("You’re near your destination")
        .setContentText("You entered your selected alert radius.")
        .setOngoing(true)
        .setAutoCancel(false)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_MAX)
        .setContentIntent(openAppIntent())
        .addAction(0, "Dismiss alarm", serviceAction(ACTION_DISMISS, 3))
        .addAction(0, "Stop journey", serviceAction(ACTION_STOP, 4))
        .build()

    private fun testAlarmNotification() = NotificationCompat.Builder(this, CHANNEL_STATUS)
        .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
        .setContentTitle("Destination Alarm Search · Test Alarm")
        .setContentText("Tap Stop test alarm to end playback.")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_ALARM)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openAppIntent())
        .addAction(0, "Stop test alarm", serviceAction(ACTION_STOP_TEST_ALARM, 5))
        .build()

    private fun updateStatusNotification() {
        updateStatusNotification(null)
    }

    private fun updateStatusNotification(messageOverride: String?) {
        val text = messageOverride ?: if (tracking) {
            val distance = currentLatitude?.let { lat -> currentLongitude?.let { lon -> distanceFrom(lat, lon) } }
            distance?.let { "${formatDistance(it)} remaining · alert at ${radiusMetres.toInt()} m" }
                ?: "Waiting for a location fix"
        } else {
            "Journey service is starting"
        }
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, statusNotification(text))
        } catch (error: RuntimeException) {
            Log.e(TAG, "event=journey_notification_update_failed", error)
            lastError = "Android could not update the journey notification: ${error.message ?: "notification unavailable"}"
        }
    }

    private fun distanceFrom(latitude: Double, longitude: Double) =
        ArrivalRules.distanceMetres(latitude, longitude, destinationLatitude, destinationLongitude)

    private fun formatDistance(metres: Double) = if (metres >= 1_000) {
        "%.2f km".format(metres / 1_000)
    } else {
        "${metres.toInt()} m"
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            1,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, JourneyForegroundService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun restoreJourney() {
        journeySaved = prefs.getBoolean("journeySaved", false)
        alarmLifecycle.restore(prefs.getBoolean("arrived", false))
        destinationLatitude = prefs.getString("destinationLatitude", "0")!!.toDouble()
        destinationLongitude = prefs.getString("destinationLongitude", "0")!!.toDouble()
        radiusMetres = prefs.getString("radiusMetres", "2000")!!.toDouble()
        currentLatitude = prefs.getString("currentLatitude", null)?.toDoubleOrNull()
        currentLongitude = prefs.getString("currentLongitude", null)?.toDoubleOrNull()
        arrivalGate = ArrivalGate(radiusMetres, arrived)
    }

    private fun saveJourney() {
        prefs.edit()
            .putBoolean("journeySaved", journeySaved)
            .putBoolean("arrived", arrived)
            .putString("destinationLatitude", destinationLatitude.toString())
            .putString("destinationLongitude", destinationLongitude.toString())
            .putString("radiusMetres", radiusMetres.toString())
            .apply()
        persistCurrentLocation()
    }

    private fun persistCurrentLocation() {
        prefs.edit()
            .putString("currentLatitude", currentLatitude?.toString())
            .putString("currentLongitude", currentLongitude?.toString())
            .apply()
    }

    private fun persistState() {
        prefs.edit()
            .putBoolean("journeySaved", journeySaved)
            .putBoolean("arrived", arrived)
            .apply()
        persistCurrentLocation()
    }

    private fun snapshot(explicitDistance: Double? = null): Map<String, Any?> {
        val distance = explicitDistance ?: currentLatitude?.let { lat ->
            currentLongitude?.let { lon -> distanceFrom(lat, lon) }
        }
        return mapOf(
            "serviceRunning" to (runningInstance === this && !stopping),
            "tracking" to tracking,
            "arrived" to arrived,
            "arrivalEvent" to false,
            "alarmSounding" to alarmSounding,
            "testAlarmActive" to testAlarmActive,
            "journeySaved" to journeySaved,
            "userStopped" to prefs.getBoolean("userStopped", false),
            "destinationLatitude" to if (journeySaved) destinationLatitude else null,
            "destinationLongitude" to if (journeySaved) destinationLongitude else null,
            "radiusMetres" to if (journeySaved) radiusMetres else null,
            "currentLatitude" to currentLatitude,
            "currentLongitude" to currentLongitude,
            "remainingMetres" to distance,
            "error" to lastError,
        )
    }

    private fun emitState(distance: Double? = null) {
        prefs.edit().also { editor ->
            if (lastError == null) editor.remove("lastError") else editor.putString("lastError", lastError)
        }.apply()
        val state = snapshot(distance).toMutableMap()
        if (arrivalEventPending) {
            state["arrivalEvent"] = true
            arrivalEventPending = false
            Log.i(TAG, "event=arrival_event_emitted_once elapsedRealtimeMs=${SystemClock.elapsedRealtime()} radiusMetres=$radiusMetres")
        }
        sendEvent(this, state)
    }

    override fun onDestroy() {
        Log.i(TAG, "event=service_destroyed arrived=$arrived tracking=$tracking alarmSounding=$alarmSounding testAlarmActive=$testAlarmActive")
        releaseAlarmAudio()
        handler.removeCallbacks(locationWatchdog)
        removeLocationUpdates()
        tracking = false
        val removalTask = locationRemovalTask
        if (removalTask == null || removalTask.isComplete) {
            locationCallbackExecutor.shutdown()
        } else {
            removalTask.addOnCompleteListener { locationCallbackExecutor.shutdown() }
        }
        if (runningInstance === this) runningInstance = null
        super.onDestroy()
    }
}
