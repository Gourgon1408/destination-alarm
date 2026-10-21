package com.destinationalarm.placesearch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    companion object {
        private const val SERVICE_CHANNEL = "destination_alarm/service"
        private const val EVENTS_CHANNEL = "destination_alarm/service_events"
        private const val REQUEST_POST_NOTIFICATIONS = 7401
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var eventSink: EventChannel.EventSink? = null
    private var pendingStartResult: MethodChannel.Result? = null
    private var pendingPermissionResult: MethodChannel.Result? = null
    private var receiverRegistered = false
    private val logTag = "DAS_ALARM"

    private val startTimeout = Runnable {
        val result = pendingStartResult ?: return@Runnable
        pendingStartResult = null
        JourneyForegroundService.stop(this)
        result.error(
            "service_start_timeout",
            "The location service did not confirm startup. Check GPS, permissions, and Google Play services, then retry.",
            null,
        )
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.let(::snapshotFromIntent) ?: return
            Log.i(logTag, "event=arrival_state_received_by_activity arrived=${state["arrived"]} tracking=${state["tracking"]} alarmSounding=${state["alarmSounding"]} testAlarmActive=${state["testAlarmActive"]} error=${state["error"]}")
            if (state["arrivalEvent"] == true) Log.i(logTag, "event=arrival_event_received_by_activity exactlyOnce=true")
            eventSink?.success(state)
            val pending = pendingStartResult ?: return
            if (state["tracking"] == true) {
                pendingStartResult = null
                mainHandler.removeCallbacks(startTimeout)
                pending.success(state)
            } else if (state["serviceRunning"] != true && state["error"] is String) {
                pendingStartResult = null
                mainHandler.removeCallbacks(startTimeout)
                pending.error("service_start_failed", state["error"] as String, state)
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val filter = IntentFilter(JourneyForegroundService.ACTION_SERVICE_EVENT)
        ContextCompat.registerReceiver(this, serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        receiverRegistered = true

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENTS_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    eventSink = events
                }

                override fun onCancel(arguments: Any?) {
                    eventSink = null
                }
            })

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SERVICE_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getStatus" -> result.success(JourneyForegroundService.status(this))
                    "acknowledgeUserStop" -> {
                        JourneyForegroundService.acknowledgeUserStop(this)
                        result.success(null)
                    }
                    "requestNotificationPermission" -> requestNotificationPermission(result)
                    "openNotificationSettings" -> openNotificationSettings(result)
                    "startJourney" -> startJourney(call.arguments as? Map<*, *>, result)
                    "stopJourney" -> {
                        JourneyForegroundService.stop(this)
                        result.success(null)
                    }
                    "dismissAlarm" -> {
                        Log.i(logTag, "event=alarm_dismiss_requested source=flutter")
                        JourneyForegroundService.dismiss(this)
                        result.success(null)
                    }
                    "testAlarm" -> startTestAlarm(result)
                    "stopTestAlarm" -> {
                        Log.i(logTag, "event=test_alarm_stop_requested source=flutter")
                        JourneyForegroundService.stopTest(this)
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun startTestAlarm(result: MethodChannel.Result) {
        val current = JourneyForegroundService.status(this)
        if (current["alarmSounding"] == true && current["arrived"] == true) {
            result.error("arrival_alarm_active", "Dismiss the active arrival alarm before testing playback.", null)
            return
        }
        try {
            val serviceIntent = Intent(this, JourneyForegroundService::class.java)
                .setAction(JourneyForegroundService.ACTION_TEST_ALARM)
            ContextCompat.startForegroundService(this, serviceIntent)
            Log.i(logTag, "event=test_alarm_requested tracking=${current["tracking"]} journeySaved=${current["journeySaved"]}")
            result.success(true)
        } catch (error: SecurityException) {
            Log.e(logTag, "event=test_alarm_request_denied", error)
            result.error("test_alarm_permission", "Android denied alarm-test playback startup.", error.message)
        } catch (error: RuntimeException) {
            Log.e(logTag, "event=test_alarm_request_failed", error)
            result.error("test_alarm_start_failed", error.message ?: "Could not start alarm playback test.", null)
        }
    }

    private fun startJourney(arguments: Map<*, *>?, result: MethodChannel.Result) {
        if (pendingStartResult != null) {
            result.error("service_starting", "Journey monitoring is already starting.", null)
            return
        }
        val hasLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocationPermission) {
            result.error("location_permission", "Allow location while using the app before starting a journey.", null)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            result.error(
                "notification_permission",
                "Allow notifications before starting the foreground journey service.",
                null,
            )
            return
        }
        if (!notificationsEnabled()) {
            result.error(
                "notifications_disabled",
                "Enable Destination Alarm notifications in Android settings before starting a journey.",
                null,
            )
            return
        }
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !manager.isLocationEnabled) {
            result.error("location_disabled", "Location services are off. Turn on GPS/location and retry.", null)
            return
        }
        val latitude = (arguments?.get("latitude") as? Number)?.toDouble()
        val longitude = (arguments?.get("longitude") as? Number)?.toDouble()
        val radiusMetres = (arguments?.get("radiusMetres") as? Number)?.toDouble()
        if (latitude == null || longitude == null || radiusMetres == null) {
            result.error("invalid_journey", "Destination coordinates and an alert radius are required.", null)
            return
        }
        val current = JourneyForegroundService.status(this)
        if (current["tracking"] == true) {
            result.success(current)
            return
        }
        if (current["serviceRunning"] == true || current["arrived"] == true) {
            result.error("journey_already_active", "Stop the active or completed journey before starting another.", null)
            return
        }

        pendingStartResult = result
        mainHandler.postDelayed(startTimeout, 15_000L)
        try {
            val serviceIntent = Intent(this, JourneyForegroundService::class.java)
                .setAction(JourneyForegroundService.ACTION_START)
                .putExtra("latitude", latitude)
                .putExtra("longitude", longitude)
                .putExtra("radiusMetres", radiusMetres)
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (error: SecurityException) {
            pendingStartResult = null
            mainHandler.removeCallbacks(startTimeout)
            result.error(
                "foreground_service_permission",
                "Android denied location service startup. Confirm location and notification permissions, then retry.",
                error.message,
            )
        } catch (error: RuntimeException) {
            pendingStartResult = null
            mainHandler.removeCallbacks(startTimeout)
            result.error("foreground_service_start_failed", error.message ?: "Could not start the service.", null)
        }
    }

    private fun requestNotificationPermission(result: MethodChannel.Result) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            result.success(notificationsEnabled())
            return
        }
        if (pendingPermissionResult != null) {
            result.error("permission_request_in_progress", "Notification permission is being requested.", null)
            return
        }
        pendingPermissionResult = result
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_POST_NOTIFICATIONS,
        )
    }

    private fun notificationsEnabled(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val statusChannel = manager.getNotificationChannel("journey_status")
            val alarmChannel = manager.getNotificationChannel("arrival_alarm")
            if (statusChannel?.importance == NotificationManager.IMPORTANCE_NONE ||
                alarmChannel?.importance == NotificationManager.IMPORTANCE_NONE
            ) return false
        }
        return true
    }

    private fun openNotificationSettings(result: MethodChannel.Result) {
        try {
            val settingsIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(settingsIntent)
            result.success(null)
        } catch (error: RuntimeException) {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            startActivity(fallback)
            result.success(null)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_POST_NOTIFICATIONS) return
        val permissionResult = pendingPermissionResult ?: return
        pendingPermissionResult = null
        permissionResult.success(
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && notificationsEnabled(),
        )
    }

    private fun snapshotFromIntent(intent: Intent): Map<String, Any?> = mapOf(
        "serviceRunning" to intent.getBooleanExtra("serviceRunning", false),
        "tracking" to intent.getBooleanExtra("tracking", false),
        "arrived" to intent.getBooleanExtra("arrived", false),
        "arrivalEvent" to intent.getBooleanExtra("arrivalEvent", false),
        "alarmSounding" to intent.getBooleanExtra("alarmSounding", false),
        "testAlarmActive" to intent.getBooleanExtra("testAlarmActive", false),
        "journeySaved" to intent.getBooleanExtra("journeySaved", false),
        "userStopped" to intent.getBooleanExtra("userStopped", false),
        "destinationLatitude" to if (intent.hasExtra("destinationLatitude")) intent.getDoubleExtra("destinationLatitude", 0.0) else null,
        "destinationLongitude" to if (intent.hasExtra("destinationLongitude")) intent.getDoubleExtra("destinationLongitude", 0.0) else null,
        "radiusMetres" to if (intent.hasExtra("radiusMetres")) intent.getDoubleExtra("radiusMetres", 0.0) else null,
        "currentLatitude" to if (intent.hasExtra("currentLatitude")) intent.getDoubleExtra("currentLatitude", 0.0) else null,
        "currentLongitude" to if (intent.hasExtra("currentLongitude")) intent.getDoubleExtra("currentLongitude", 0.0) else null,
        "remainingMetres" to if (intent.hasExtra("remainingMetres")) intent.getDoubleExtra("remainingMetres", 0.0) else null,
        "error" to intent.getStringExtra("error"),
    )

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(serviceReceiver)
            receiverRegistered = false
        }
        pendingStartResult?.let {
            mainHandler.removeCallbacks(startTimeout)
            it.error("activity_closed", "The app screen closed while starting journey monitoring.", null)
            pendingStartResult = null
        }
        pendingPermissionResult?.let {
            it.success(false)
            pendingPermissionResult = null
        }
        super.onDestroy()
    }
}
