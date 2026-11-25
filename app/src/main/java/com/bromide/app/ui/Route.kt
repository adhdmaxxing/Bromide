package com.bromide.app.ui

/** Screens of the app; MainActivity keeps a simple back stack of these. */
sealed interface Route {
    data object Home : Route
    data object Library : Route
    /** One film and its settings; [forPhone] when opened to choose the phone photos' film. */
    data class Film(val id: String, val forPhone: Boolean = false) : Route
    data object Gallery : Route
    /** One photo, swiping through the others that pass [filter]. */
    data class Viewer(val uri: String, val filter: GalleryFilter = GalleryFilter.All) : Route
    /** First-run setup, adding a camera, or (with [cameraId]) the setup guide for one of yours. */
    data class Onboarding(val cameraId: String? = null) : Route
    data object Cameras : Route
    data object Settings : Route
    data object FromCard : Route
    /** Develop photos taken with this phone. */
    data object PhonePhotos : Route
}
