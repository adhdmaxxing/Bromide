package com.bromide.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bromide.app.ui.AppState
import com.bromide.app.ui.CamerasScreen
import com.bromide.app.ui.FB
import com.bromide.app.ui.BromideTheme
import com.bromide.app.ui.FilmScreen
import com.bromide.app.ui.FromCardScreen
import com.bromide.app.ui.GalleryScreen
import com.bromide.app.ui.HomeScreen
import com.bromide.app.ui.LibraryScreen
import com.bromide.app.ui.OnboardingScreen
import com.bromide.app.ui.PhonePhotosScreen
import com.bromide.app.ui.Route
import com.bromide.app.ui.SettingsScreen
import com.bromide.app.ui.ViewerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = (application as BromideApp).settings
        setContent {
            BromideTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = FB.Background) {
                    val app = remember { AppState(settings, applicationContext) }
                    val stack = remember {
                        mutableStateListOf<Route>(Route.Home).apply { if (!settings.setupDone) add(Route.Onboarding()) }
                    }
                    val nav: (Route) -> Unit = { stack.add(it) }
                    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
                    BackHandler(enabled = stack.size > 1) { back() }
                    when (val r = stack.last()) {
                        Route.Home -> HomeScreen(app, nav)
                        Route.Library -> LibraryScreen(app, nav, back)
                        is Route.Film -> FilmScreen(app, r.id, r.forPhone, back)
                        Route.Gallery -> GalleryScreen(app, nav, back)
                        is Route.Viewer -> ViewerScreen(r.uri, r.filter, back)
                        is Route.Onboarding -> OnboardingScreen(app, r.cameraId, onDone = back,
                                                                onFromCard = { back(); nav(Route.FromCard) },
                                                                onPhone = { back(); nav(Route.PhonePhotos) })
                        Route.Cameras -> CamerasScreen(app, nav, back)
                        Route.Settings -> SettingsScreen(app, nav, back)
                        Route.FromCard -> FromCardScreen(app, back)
                        Route.PhonePhotos -> PhonePhotosScreen(app, nav, back)
                    }
                }
            }
        }
    }
}
