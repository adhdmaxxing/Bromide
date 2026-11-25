package com.bromide.app

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class Developed(
    val uri: Uri,
    val name: String,
    val stockName: String,
    val printEv: Float,
    val millis: Long,
    val fromRaw: Boolean = false,
    /** Bumped when the photo is replaced (JPEG version -> RAW version). */
    val version: Int = 0,
    /** The camera it came from, if known. */
    val cameraId: String? = null,
    val cameraName: String? = null,
)

data class Status(
    val running: Boolean = false,
    val port: Int = 2121,
    val addresses: List<LocalAddress> = emptyList(),
    /** An FTP camera connected this session. */
    val cameraSeen: Boolean = false,
    val cameraUser: String? = null,
    val received: Int = 0,
    val developed: Int = 0,
    val queued: Int = 0,
    /** Which sources run this session: the FTP server, the camera-app watcher. */
    val ftpOn: Boolean = false,
    val watchOn: Boolean = false,
    /** The camera-app watcher can't run: Bromide may not read the phone's photos. */
    val needsPhotoAccess: Boolean = false,
    val recent: List<Developed> = emptyList(),
    /** Bumped whenever a new photo arrives (refreshes "your last photo" previews). */
    val lastPhotoVersion: Int = 0,
    /** The phone address the camera connected to this session. */
    val cameraHost: String? = null,
    val error: String? = null,
    val log: List<String> = emptyList(),
)

/** Shared between the background service and the UI. */
object ServiceState {
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    fun update(f: (Status) -> Status) = _status.update(f)

    fun log(line: String) = _status.update { it.copy(log = (it.log + line).takeLast(60)) }
}
