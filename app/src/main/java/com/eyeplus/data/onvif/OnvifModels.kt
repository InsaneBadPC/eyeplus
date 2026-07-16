package com.eyeplus.data.onvif

/**
 * Data classes for ONVIF device information and configuration.
 */
data class OnvifDeviceInfo(
    val host: String,
    val port: Int = 80,
    val username: String = "admin",
    val password: String = "admin"
)

data class MediaProfile(
    val token: String,
    val name: String,
    val videoSourceToken: String? = null,
    val videoEncoderToken: String? = null,
    val audioEncoderToken: String? = null,
    val ptzConfigurationToken: String? = null
)

data class StreamUri(
    val uri: String,
    val protocol: String = "RTSP"
)

data class PtzPosition(
    val x: Float = 0f,
    val y: Float = 0f,
    val zoom: Float = 0f
)

data class OnvifServices(
    val deviceService: String = "/onvif/device_service",
    val mediaService: String = "/onvif/media_service",
    val media2Service: String = "/onvif/media2_service" ,
    val ptzService: String = "/onvif/ptz_service",
    val eventsService: String = "/onvif/events_service",
    val imagingService: String = "/onvif/imaging_service"
)
