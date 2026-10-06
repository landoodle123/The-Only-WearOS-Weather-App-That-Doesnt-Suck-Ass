package com.landoodle11.theonlywearosweatherappthatdoesntsuckass.complication

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return makeComplication("72°F")
    }

    override suspend fun onComplicationRequest(
        request: ComplicationRequest,
    ): ComplicationData {
        if (request.complicationType != ComplicationType.SHORT_TEXT) {
            return makeComplication("—°")
        }

        val temperature = withContext(Dispatchers.IO) {
            try {
                getLatestTemperature()
            } catch (_: Exception) {
                null
            }
        }

        return makeComplication(temperature ?: "—°")
    }

    private fun getLatestTemperature(): String? {
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocationPermission) return null

        val fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val location = try {
            Tasks.await(fusedClient.lastLocation, 10, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        } ?: return null

        val point = getJson(
            "https://api.weather.gov/points/${location.latitude},${location.longitude}"
        )
        val stationsUrl = point
            .optJSONObject("properties")
            ?.optString("observationStations")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null

        val features = getJson(stationsUrl).optJSONArray("features")
            ?: return null
        if (features.length() == 0) return null

        val stationId = features
            .optJSONObject(0)
            ?.optJSONObject("properties")
            ?.optString("stationIdentifier")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: return null

        val observation = getJson(
            "https://api.weather.gov/stations/$stationId/observations/latest"
        )
        val temperatureC = observation
            .optJSONObject("properties")
            ?.optJSONObject("temperature")
            ?.optDouble("value", Double.NaN)
            ?: Double.NaN

        if (!temperatureC.isFinite()) return null

        val temperatureF = temperatureC * 9.0 / 5.0 + 32.0
        return String.format(Locale.US, "%.0f°F", temperatureF)
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/geo+json")
            // Replace this with your app name and a real contact address.
            connection.setRequestProperty(
                "User-Agent",
                "TheOnlyWearOSWeatherApp (contact: you@example.com)",
            )

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("NWS returned HTTP $code")
            }

            return connection.inputStream.bufferedReader().use {
                JSONObject(it.readText())
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun makeComplication(text: String): ShortTextComplicationData {
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(
                if (text == "—°") "Temperature unavailable" else "Temperature $text"
            ).build(),
        ).build()
    }
}
