package com.landoodle11.theonlywearosweatherappthatdoesntsuckass.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.landoodle11.theonlywearosweatherappthatdoesntsuckass.presentation.theme.TheOnlyWearOSWeatherAppThatDoesntSuckAssTheme
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var latitude by mutableStateOf<Double?>(null)
    private var longitude by mutableStateOf<Double?>(null)
    private var locationMessage by mutableStateOf("Waiting for location permission…")
    private var weatherLines by mutableStateOf<List<String>>(emptyList())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasLocationPermission()) {
                getCurrentLocation()
            } else {
                locationMessage = "Location permission was not granted."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            WearApp(
                latitude = latitude,
                longitude = longitude,
                locationMessage = locationMessage,
                weatherLines = weatherLines,
                onRefresh = { getCurrentLocation() },
            )
        }

        if (!hasLocationPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    /**override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            getCurrentLocation()
        }
    }**/

    override fun onDestroy() {
        super.onDestroy()
        networkExecutor.shutdownNow()
    }

    private fun hasLocationPermission(): Boolean {
        val fineGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        return fineGranted || coarseGranted
    }

    private fun getCurrentLocation() {
        if (!hasLocationPermission()) {
            locationMessage = "Location permission is not granted."
            return
        }

        locationMessage = "Getting location…"

        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                CancellationTokenSource().token,
            )
                .addOnSuccessListener { location ->
                    if (location != null) {
                        updateLocation(location)
                    } else {
                        locationMessage = "No location fix. Try again or set emulator location."
                    }
                }
                .addOnFailureListener { error ->
                    locationMessage =
                        "Location failed: ${error.localizedMessage ?: "unknown error"}"
                }
        } catch (_: SecurityException) {
            locationMessage = "Location permission is required."
        }
    }

    private fun updateLocation(location: Location) {
        latitude = location.latitude
        longitude = location.longitude
        locationMessage = "Coordinates received"

        loadNwsWeather(location.latitude, location.longitude)
    }

    private fun loadNwsWeather(lat: Double, lon: Double) {
        weatherLines = listOf("Looking up nearby NWS station…")

        networkExecutor.execute {
            try {
                val point = getJson("https://api.weather.gov/points/$lat,$lon")
                val pointProperties = point.getJSONObject("properties")

                val stationUrl = pointProperties.getString("observationStations")
                val forecastUrl = pointProperties.optString("forecast")

                val stationList = getJson(stationUrl)
                val features = stationList.optJSONArray("features")

                if (features == null || features.length() == 0) {
                    runOnUiThread {
                        weatherLines = listOf("No nearby NWS observation station was found.")
                    }
                    return@execute
                }

                val stationProperties =
                    features.getJSONObject(0).getJSONObject("properties")
                val stationName = stationProperties.optString("name", "Nearby station")
                val stationId = stationProperties.optString("stationIdentifier", "")

                val lines = mutableListOf<String>()
                lines += "Station: $stationName"
                if (stationId.isNotBlank()) lines += "Station ID: $stationId"

                if (stationId.isNotBlank()) {
                    val observation = getJson(
                        "https://api.weather.gov/stations/$stationId/observations/latest"
                    )
                    val props = observation.optJSONObject("properties")

                    if (props != null) {
                        val description = props.optString("textDescription")
                        if (description.isNotBlank() && description != "null") {
                            lines += "Conditions: $description"
                        }

                        lines += "Temperature: ${
                            formatTemperature(props.optJSONObject("temperature"))
                        }"
                        lines += "Feels like: ${
                            formatTemperature(props.optJSONObject("windChill"))
                        }"
                        lines += "Humidity: ${
                            formatQuantity(props.optJSONObject("relativeHumidity"), "%")
                        }"
                        lines += "Wind: ${
                            formatQuantity(props.optJSONObject("windSpeed"), "km/h")
                        } ${formatDirection(props.optJSONObject("windDirection"))}"
                        lines += "Dew point: ${
                            formatTemperature(props.optJSONObject("dewpoint"))
                        }"
                        lines += "Pressure: ${
                            formatPressure(props.optJSONObject("barometricPressure"))
                        }"
                        lines += "Visibility: ${
                            formatQuantity(props.optJSONObject("visibility"), "km")
                        }"

                        val timestamp = props.optString("timestamp")
                        if (timestamp.isNotBlank() && timestamp != "null") {
                            lines += "Observed: $timestamp"
                        }
                    }
                }

                if (forecastUrl.isNotBlank()) {
                    val forecast = getJson(forecastUrl)
                    val periods = forecast
                        .optJSONObject("properties")
                        ?.optJSONArray("periods")

                    if (periods != null && periods.length() > 0) {
                        lines += "Forecast"
                        for (i in 0 until minOf(3, periods.length())) {
                            val period = periods.getJSONObject(i)
                            val name = period.optString("name", "Upcoming")
                            val temp = period.optString("temperature", "")
                            val unit = period.optString("temperatureUnit", "")
                            val forecastText = period.optString("shortForecast", "")
                            val precipitation = period
                                .optJSONObject("probabilityOfPrecipitation")
                                ?.optString("value")
                                ?.takeIf { it != "null" && it.isNotBlank() }

                            var forecastLine = "$name: $temp°$unit"
                            if (forecastText.isNotBlank()) {
                                forecastLine += ", $forecastText"
                            }
                            if (precipitation != null) {
                                forecastLine += ", rain $precipitation%"
                            }
                            lines += forecastLine
                        }
                    }
                }

                runOnUiThread {
                    locationMessage = "Weather updated"
                    weatherLines = lines
                }
            } catch (e: Exception) {
                runOnUiThread {
                    locationMessage = "Weather lookup failed"
                    weatherLines = listOf(
                        e.localizedMessage ?: "Could not retrieve NWS weather data."
                    )
                }
            }
        }
    }

    private fun getJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/geo+json")
            // Replace with your app name and a contact address if you have one.
            connection.setRequestProperty(
                "User-Agent",
                "TheOnlyWearOSWeatherApp (contact: you@example.com)",
            )

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream.bufferedReader().use { it.readText() }
            if (code !in 200..299) {
                throw IllegalStateException("NWS returned HTTP $code: $body")
            }
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun formatTemperature(value: JSONObject?): String {
        val celsius = value?.optDouble("value", Double.NaN) ?: Double.NaN
        if (!celsius.isFinite()) return "—"

        val fahrenheit = celsius * 9.0 / 5.0 + 32.0
        return String.format(Locale.US, "%.1f°C / %.1f°F", celsius, fahrenheit)
    }

    private fun formatQuantity(value: JSONObject?, targetUnit: String): String {
        val number = value?.optDouble("value", Double.NaN) ?: Double.NaN
        if (!number.isFinite()) return "—"

        val unitCode = value?.optString("unitCode").orEmpty()
        val converted = when {
            targetUnit == "km/h" && unitCode.endsWith("km_h-1") -> number
            targetUnit == "km/h" && unitCode.endsWith("m_s-1") -> number * 3.6
            targetUnit == "km" && unitCode.endsWith("m") -> number / 1000.0
            targetUnit == "%" -> number
            else -> number
        }

        return String.format(Locale.US, "%.1f %s", converted, targetUnit)
    }

    private fun formatPressure(value: JSONObject?): String {
        val pascals = value?.optDouble("value", Double.NaN) ?: Double.NaN
        if (!pascals.isFinite()) return "—"

        val hpa = pascals / 100.0
        val inHg = pascals / 3386.389
        return String.format(Locale.US, "%.1f hPa / %.2f inHg", hpa, inHg)
    }

    private fun formatDirection(value: JSONObject?): String {
        val degrees = value?.optDouble("value", Double.NaN) ?: Double.NaN
        if (!degrees.isFinite()) return ""

        val directions = listOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
        )
        val direction = directions[
            (((degrees / 22.5) + 0.5).toInt()) % directions.size
        ]
        return "($direction)"
    }
    private val refreshHandler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (hasLocationPermission()) {
                getCurrentLocation()
            }
            refreshHandler.postDelayed(this, 15 * 60 * 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            getCurrentLocation()
            refreshHandler.postDelayed(refreshRunnable, 15 * 60 * 1000L)
        }
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

}


@Composable
fun WearApp(
    latitude: Double?,
    longitude: Double?,
    locationMessage: String,
    weatherLines: List<String>,
    onRefresh: () -> Unit,
) {
    val locationText = if (latitude != null && longitude != null) {
        String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    } else {
        locationMessage
    }

    TheOnlyWearOSWeatherAppThatDoesntSuckAssTheme {
        AppScaffold {
            val listState = rememberTransformingLazyColumnState()
            val transformationSpec = rememberTransformationSpec()

            ScreenScaffold(scrollState = listState) { contentPadding ->
                TransformingLazyColumn(
                    contentPadding = contentPadding,
                    state = listState,
                ) {
                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("Your location")
                        }
                    }

                    item {
                        Text(
                            text = locationText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)

                        )
                    }

                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text(locationMessage)
                        }
                    }

                    weatherLines.forEach { line ->
                        item {
                            Text(
                                text = line,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)

                            )
                        }
                    }

                    item {
                        Button(
                            onClick = onRefresh,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text("Refresh")
                        }
                    }
                }
            }
        }
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@Composable
fun DefaultPreview() {
    WearApp(
        latitude = 37.421998,
        longitude = -122.084,
        locationMessage = "Preview data",
        weatherLines = listOf(
            "Station: Example station",
            "Conditions: Clear",
            "Temperature: 20.0°C / 68.0°F",
            "Humidity: 45%",
            "Forecast: Mostly sunny",
        ),
        onRefresh = {},
    )
}
