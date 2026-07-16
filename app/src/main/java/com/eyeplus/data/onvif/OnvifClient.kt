package com.eyeplus.data.onvif

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ONVIF SOAP/XML client for the EYEPLUS PTZ camera.
 * Communicates with the camera over HTTP using SOAP XML messages
 * with WS-UsernameToken digest authentication.
 */
class OnvifClient(
    private val host: String,
    private val port: Int = 80,
    private val username: String = "admin",
    private val password: String = "admin"
) {
    companion object {
        private const val TAG = "OnvifClient"
        private const val SOAP_MEDIA_TYPE = "application/soap+xml; charset=utf-8"
        private val SOAP_MEDIA = SOAP_MEDIA_TYPE.toMediaType()

        // Standard ONVIF endpoints
        const val DEVICE_SERVICE = "/onvif/device_service"
        const val MEDIA_SERVICE = "/onvif/media_service"
        const val MEDIA2_SERVICE = "/onvif/media2_service"
        const val PTZ_SERVICE = "/onvif/ptz_service"
        const val EVENTS_SERVICE = "/onvif/events_service"

        // HTTP CGI PTZ commands (fallback method)
        private val PTZ_COMMANDS = mapOf(
            "UP" to "0",
            "DOWN" to "2",
            "LEFT" to "4",
            "RIGHT" to "6",
            "STOP" to "1"
        )

        // XML namespaces
        val WSSE_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"
        val WSU_NS = "http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd"
        val SOAP_ENV_NS = "http://www.w3.org/2003/05/soap-envelope"
        val TD_NS = "http://www.onvif.org/ver10/device/wsdl"
        val TRT_NS = "http://www.onvif.org/ver10/media/wsdl"
        val TDS_NS = "http://www.onvif.org/ver20/ptz/wsdl"
        val TYPES_NS = "http://www.onvif.org/ver10/schema"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
        .build()

    private val cachedProfiles = mutableListOf<MediaProfile>()
    private var cachedServices: OnvifServices? = null

    private val baseUrl: String get() = "http://$host:$port"

    // ──────────────────────────────────────────────
    // WS-UsernameToken Digest Authentication
    // ──────────────────────────────────────────────

    private fun createWsSecurityHeader(): String {
        val nonce = ByteArray(20).apply { SecureRandom().nextBytes(this) }
        val created = Instant.now().toString()
        val formattedCreated = created.replace("Z", "000Z")

        // SHA1(nonce + created + password)
        val digestInput = nonce + formattedCreated.toByteArray() + password.toByteArray()
        val digest = MessageDigest.getInstance("SHA-1").digest(digestInput)

        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        val digestB64 = Base64.encodeToString(digest, Base64.NO_WRAP)

        return """
            |<wsse:Security xmlns:wsse="$WSSE_NS" xmlns:wsu="$WSU_NS" mustUnderstand="true">
            |  <wsse:UsernameToken wsu:Id="UsernameToken-1">
            |    <wsse:Username>$username</wsse:Username>
            |    <wsse:Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digestB64</wsse:Password>
            |    <wsse:Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0">$nonceB64</wsse:Nonce>
            |    <wsu:Created>$formattedCreated</wsu:Created>
            |  </wsse:UsernameToken>
            |</wsse:Security>
        """.trimMargin()
    }

    private fun createSoapEnvelope(body: String, security: Boolean = true): String {
        val header = if (security) createWsSecurityHeader() else ""
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="$SOAP_ENV_NS">
  <s:Header>$header</s:Header>
  <s:Body>$body</s:Body>
</s:Envelope>"""
    }

    // ──────────────────────────────────────────────
    // HTTP Request Helper
    // ──────────────────────────────────────────────

    private suspend fun sendSoap(endpoint: String, bodyXml: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val envelope = createSoapEnvelope(bodyXml)
                val requestBody = envelope.toRequestBody(SOAP_MEDIA)
                val request = Request.Builder()
                    .url(baseUrl + endpoint)
                    .addHeader("Content-Type", SOAP_MEDIA_TYPE)
                    .post(requestBody)
                    .build()

                Log.d(TAG, "Sending SOAP to $endpoint")
                Log.d(TAG, "Request: $envelope")

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    Log.d(TAG, "Response code: ${response.code}")
                    Log.d(TAG, "Response: ${body?.take(500)}")
                    body
                }
            } catch (e: Exception) {
                Log.e(TAG, "SOAP request failed: ${e.message}", e)
                null
            }
        }
    }

    // ──────────────────────────────────────────────
    // GetServices — discover available endpoints
    // ──────────────────────────────────────────────

    suspend fun getServices(): OnvifServices {
        if (cachedServices != null) return cachedServices!!

        val body = """
            <GetServices xmlns="$TD_NS">
                <IncludeCapability>true</IncludeCapability>
            </GetServices>
        """.trimIndent()

        val response = sendSoap(DEVICE_SERVICE, body)
        // Default services; camera likely uses standard paths
        cachedServices = OnvifServices()
        return cachedServices!!
    }

    // ──────────────────────────────────────────────
    // GetProfiles — retrieve media profiles with tokens
    // ──────────────────────────────────────────────

    suspend fun getProfiles(): List<MediaProfile> {
        if (cachedProfiles.isNotEmpty()) return cachedProfiles

        val body = """
            <GetProfiles xmlns="$TRT_NS"/>
        """.trimIndent()

        val response = sendSoap(MEDIA_SERVICE, body) ?: return emptyList()
        cachedProfiles.clear()
        cachedProfiles.addAll(parseProfiles(response))
        return cachedProfiles.toList()
    }

    private fun parseProfiles(xml: String): List<MediaProfile> {
        val profiles = mutableListOf<MediaProfile>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var token: String? = null
            var name: String? = null
            var videoSource: String? = null
            var videoEncoder: String? = null
            var audioEncoder: String? = null
            var ptzConfig: String? = null
            var inProfile = false
            var inVideoSource = false
            var inVideoEncoder = false
            var inAudioEncoder = false
            var inPTZConfig = false
            var currentTag = ""

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name
                        when (parser.name) {
                            "Profiles" -> {
                                inProfile = true
                                token = parser.getAttributeValue(null, "token")
                                name = null
                                videoSource = null
                                videoEncoder = null
                                audioEncoder = null
                                ptzConfig = null
                            }
                            "VideoSource" -> inVideoSource = true
                            "VideoEncoderConfiguration" -> inVideoEncoder = true
                            "AudioEncoderConfiguration" -> inAudioEncoder = true
                            "PTZConfiguration" -> inPTZConfig = true
                        }
                    }

                    XmlPullParser.TEXT -> {
                        when {
                            inProfile && currentTag == "Name" -> name = parser.text
                            inVideoSource && currentTag == "token" -> videoSource = parser.text
                            inVideoEncoder && currentTag == "token" -> videoEncoder = parser.text
                            inAudioEncoder && currentTag == "token" -> audioEncoder = parser.text
                            inPTZConfig && currentTag == "token" -> ptzConfig = parser.text
                        }
                    }

                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "VideoSource" -> inVideoSource = false
                            "VideoEncoderConfiguration" -> inVideoEncoder = false
                            "AudioEncoderConfiguration" -> inAudioEncoder = false
                            "PTZConfiguration" -> inPTZConfig = false
                            "Profiles" -> {
                                if (inProfile && token != null) {
                                    profiles.add(MediaProfile(
                                        token = token,
                                        name = name ?: "Profile $token",
                                        videoSourceToken = videoSource,
                                        videoEncoderToken = videoEncoder,
                                        audioEncoderToken = audioEncoder,
                                        ptzConfigurationToken = ptzConfig
                                    ))
                                }
                                inProfile = false
                            }
                        }
                        currentTag = ""
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse profiles: ${e.message}", e)
        }
        return profiles
    }

    // ──────────────────────────────────────────────
    // GetStreamUri — obtain RTSP stream URL
    // ──────────────────────────────────────────────

    suspend fun getStreamUri(profileToken: String, protocol: String = "RTSP"): StreamUri? {
        val body = """
            <GetStreamUri xmlns="$TRT_NS">
                <StreamSetup>
                    <Stream xmlns="$TYPES_NS">RTP-Unicast</Stream>
                    <Transport xmlns="$TYPES_NS">
                        <Protocol>$protocol</Protocol>
                    </Transport>
                </StreamSetup>
                <ProfileToken>$profileToken</ProfileToken>
            </GetStreamUri>
        """.trimIndent()

        val response = sendSoap(MEDIA_SERVICE, body) ?: return null
        return parseStreamUri(response)
    }

    private fun parseStreamUri(xml: String): StreamUri? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var uri: String? = null
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.TEXT && parser.name == "Uri") {
                    uri = parser.text
                }
                parser.next()
            }
            uri?.let { StreamUri(uri = it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse StreamUri: ${e.message}", e)
            null
        }
    }

    /**
     * Get RTSP URL directly (uses the camera's known URL pattern as fallback).
     * ONVIF GetStreamUri is preferred; falls back to known URL pattern.
     */
    suspend fun getRtspUrl(profileIndex: Int = 0): String {
        // Try ONVIF first
        val profiles = getProfiles()
        if (profiles.isNotEmpty() && profileIndex < profiles.size) {
            val streamUri = getStreamUri(profiles[profileIndex].token)
            if (streamUri != null) return streamUri.uri
        }

        // Fallback: known RTSP URLs for EYEPLUS camera
        return when (profileIndex) {
            0 -> "rtsp://$username:$password@$host:554/0/av0"  // Main stream
            1 -> "rtsp://$username:$password@$host:554/0/av1"  // Sub stream
            else -> "rtsp://$username:$password@$host:554/0/av0"
        }
    }

    // ──────────────────────────────────────────────
    // PTZ Control
    // ──────────────────────────────────────────────

    /**
     * Move PTZ camera relatively.
     * @param panX Horizontal movement (-1.0 to 1.0)
     * @param tiltY Vertical movement (-1.0 to 1.0)
     * @param zoomX Zoom (optional, null to skip)
     * @param profileToken Profile token for PTZ configuration
     */
    suspend fun relativeMove(panX: Float, tiltY: Float, zoomX: Float? = null, profileToken: String? = null): Boolean {
        val token = profileToken ?: getFirstProfileToken() ?: return false

        val zoomXml = if (zoomX != null) {
            """<Zoom x="$zoomX" space="http://www.onvif.org/ver10/ptz/space/RelativePositionTranslation"/>"""
        } else ""

        val zoomSpeedXml = if (zoomX != null) {
            """<Zoom x="1.0" space="http://www.onvif.org/ver10/ptz/space/RelativeSpeed"/>"""
        } else ""

        val body = """
            <RelativeMove xmlns="$TDS_NS">
                <ProfileToken>$token</ProfileToken>
                <Translation>
                    <PanTilt x="$panX" y="$tiltY" space="http://www.onvif.org/ver10/ptz/space/RelativePositionTranslation"/>
                    $zoomXml
                </Translation>
                <Speed>
                    <PanTilt x="1.0" y="1.0" space="http://www.onvif.org/ver10/ptz/space/RelativeSpeed"/>
                    $zoomSpeedXml
                </Speed>
            </RelativeMove>
        """.trimIndent()

        val response = sendSoap(PTZ_SERVICE, body)
        return response != null
    }

    /**
     * Stop all PTZ movement.
     */
    suspend fun stopPtz(profileToken: String? = null): Boolean {
        val token = profileToken ?: getFirstProfileToken() ?: return false

        val body = """
            <Stop xmlns="$TDS_NS">
                <ProfileToken>$token</ProfileToken>
                <PanTilt>true</PanTilt>
                <Zoom>true</Zoom>
            </Stop>
        """.trimIndent()

        val response = sendSoap(PTZ_SERVICE, body)
        return response != null
    }

    /**
     * Get PTZ status (current position).
     */
    suspend fun getPtzStatus(profileToken: String? = null): PtzPosition? {
        val token = profileToken ?: getFirstProfileToken() ?: return null

        val body = """
            <GetStatus xmlns="$TDS_NS">
                <ProfileToken>$token</ProfileToken>
            </GetStatus>
        """.trimIndent()

        val response = sendSoap(PTZ_SERVICE, body) ?: return null
        return parsePtzStatus(response)
    }

    private fun parsePtzStatus(xml: String): PtzPosition? {
        return try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var x = 0f
            var y = 0f
            var zoom = 0f
            var inPanTilt = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "PanTilt" -> inPanTilt = true
                        }
                    }
                    XmlPullParser.TEXT -> {
                        when {
                            inPanTilt && parser.name == "x" -> x = parser.text.toFloatOrNull() ?: 0f
                            inPanTilt && parser.name == "y" -> y = parser.text.toFloatOrNull() ?: 0f
                            parser.name == "x" && !inPanTilt -> zoom = parser.text.toFloatOrNull() ?: 0f
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "PanTilt") inPanTilt = false
                    }
                }
                parser.next()
            }
            PtzPosition(x, y, zoom)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PTZ status: ${e.message}", e)
            null
        }
    }

    // ──────────────────────────────────────────────
    // HTTP CGI PTZ Control (Fallback Method B)
    // ──────────────────────────────────────────────

    /**
     * Send PTZ command via HTTP CGI (fallback when ONVIF PTZ fails).
     */
    suspend fun cgiPtzCommand(command: String): Boolean {
        val code = PTZ_COMMANDS[command.uppercase()] ?: return false
        return withContext(Dispatchers.IO) {
            try {
                val url = "$baseUrl/decoder_control.cgi?command=$code"
                val auth = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Basic $auth")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    response.isSuccessful
                }
            } catch (e: Exception) {
                Log.e(TAG, "CGI PTZ command failed: ${e.message}", e)
                false
            }
        }
    }

    // ──────────────────────────────────────────────
    // Device Information
    // ──────────────────────────────────────────────

    /**
     * Get basic device information.
     */
    suspend fun getDeviceInformation(): Map<String, String> {
        val body = """
            <GetDeviceInformation xmlns="$TD_NS"/>
        """.trimIndent()

        val response = sendSoap(DEVICE_SERVICE, body) ?: return emptyMap()
        return parseDeviceInfo(response)
    }

    private fun parseDeviceInfo(xml: String): Map<String, String> {
        val info = mutableMapOf<String, String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var currentTag = ""
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    currentTag = parser.name
                } else if (parser.eventType == XmlPullParser.TEXT) {
                    when (currentTag) {
                        "Manufacturer", "Model", "FirmwareVersion",
                        "SerialNumber", "HardwareId" -> info[currentTag] = parser.text
                    }
                }
                parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse device info: ${e.message}", e)
        }
        return info
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private suspend fun getFirstProfileToken(): String? {
        return getProfiles().firstOrNull()?.token
    }

    /**
     * Test connection to the camera.
     */
    suspend fun testConnection(): Boolean {
        return try {
            val info = getDeviceInformation()
            info.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * OkHttp logging interceptor for debugging SOAP calls.
 */
class HttpLoggingInterceptor(level: Level = Level.BODY) : Interceptor {
    enum class Level { NONE, BASIC, HEADERS, BODY }

    private var currentLevel = level

    fun setLevel(level: Level): HttpLoggingInterceptor {
        currentLevel = level
        return this
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (currentLevel == Level.NONE) return chain.proceed(request)

        Log.d("HttpLoggingInterceptor", "--> ${request.method} ${request.url}")
        if (currentLevel >= Level.HEADERS) {
            request.headers.forEach { Log.d("HttpLoggingInterceptor", "  ${it.first}: ${it.second}") }
        }
        if (currentLevel >= Level.BODY && request.body != null) {
            Log.d("HttpLoggingInterceptor", "Body: ${request.body}")
        }

        val response = chain.proceed(request)
        Log.d("HttpLoggingInterceptor", "<-- ${response.code} ${response.message}")
        return response
    }
}
