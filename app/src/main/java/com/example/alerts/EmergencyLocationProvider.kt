package com.example.alerts

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.BuildConfig
import com.example.domain.GpsLocationSnapshot
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

sealed class LocationCaptureOutcome {
    data class Success(val snapshot: GpsLocationSnapshot) : LocationCaptureOutcome()
    data class Unavailable(val reason: String) : LocationCaptureOutcome()
}

class EmergencyLocationProvider(private val context: Context) {

    private val fusedClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    private val geminiHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fineGranted || coarseGranted
    }

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    suspend fun captureCurrentLocation(
        enrichWithGeminiMapsContext: Boolean = false
    ): LocationCaptureOutcome = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) {
            return@withContext LocationCaptureOutcome.Unavailable(
                "Location permission (ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION) is not granted."
            )
        }

        val fineGranted = hasFineLocationPermission()
        val priority = if (fineGranted) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val freshLocation: Location? = withTimeoutOrNull(4500L) {
            awaitCurrentFusedLocation(priority)
        }

        val resolvedLocation: Location? = freshLocation
            ?: withTimeoutOrNull(2000L) { awaitLastFusedLocation() }
            ?: getPlatformLastKnownLocation()

        if (resolvedLocation == null) {
            return@withContext LocationCaptureOutcome.Unavailable(
                "Device GPS location is currently unavailable (ensure Location Services are turned on)."
            )
        }

        val optionalContext = if (enrichWithGeminiMapsContext) {
            withTimeoutOrNull(3500L) {
                fetchOptionalEmergencyContext(resolvedLocation.latitude, resolvedLocation.longitude)
            }
        } else {
            null
        }

        LocationCaptureOutcome.Success(
            GpsLocationSnapshot(
                latitude = resolvedLocation.latitude,
                longitude = resolvedLocation.longitude,
                accuracyMeters = resolvedLocation.accuracy,
                timestampMillis = if (resolvedLocation.time > 0L) {
                    resolvedLocation.time
                } else {
                    System.currentTimeMillis()
                },
                provider = resolvedLocation.provider ?: "fused",
                nearbyEmergencyContext = optionalContext
            )
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitCurrentFusedLocation(priority: Int): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                val cts = CancellationTokenSource()
                cont.invokeOnCancellation { cts.cancel() }
                val req = CurrentLocationRequest.Builder()
                    .setPriority(priority)
                    .setMaxUpdateAgeMillis(30_000L)
                    .setDurationMillis(4_000L)
                    .build()
                fusedClient.getCurrentLocation(req, cts.token)
                    .addOnSuccessListener { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    @SuppressLint("MissingPermission")
    private suspend fun awaitLastFusedLocation(): Location? =
        suspendCancellableCoroutine { cont ->
            try {
                fusedClient.lastLocation
                    .addOnSuccessListener { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume(null)
                    }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }

    @SuppressLint("MissingPermission")
    private fun getPlatformLastKnownLocation(): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            providers.mapNotNull { provider ->
                try {
                    if (lm.isProviderEnabled(provider)) {
                        lm.getLastKnownLocation(provider)
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
            }.maxByOrNull { it.time }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchOptionalEmergencyContext(lat: Double, lng: Double): String? =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.GEMINI_API_KEY
            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                return@withContext null
            }

            try {
                val prompt = "Provide a single concise 1-sentence geographic reference note for emergency responders for coordinates ($lat, $lng)."
                val bodyJson = JSONObject().apply {
                    put(
                        "contents",
                        JSONArray().put(
                            JSONObject().put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", prompt))
                            )
                        )
                    )
                }

                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                geminiHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val raw = response.body?.string() ?: return@withContext null
                    val json = JSONObject(raw)
                    val candidates = json.optJSONArray("candidates") ?: return@withContext null
                    val first = candidates.optJSONObject(0) ?: return@withContext null
                    val content = first.optJSONObject("content") ?: return@withContext null
                    val parts = content.optJSONArray("parts") ?: return@withContext null
                    parts.optJSONObject(0)?.optString("text")?.trim()?.takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) {
                null
            }
        }
}
