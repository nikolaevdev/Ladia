package ru.mishanikolaev.ladya

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import ru.mishanikolaev.ladya.navigation.AppNavHost
import ru.mishanikolaev.ladya.navigation.AppRoute
import ru.mishanikolaev.ladya.network.LadyaNodeRepository
import ru.mishanikolaev.ladya.notify.AppNotificationManager
import ru.mishanikolaev.ladya.service.LadyaForegroundService
import ru.mishanikolaev.ladya.ui.theme.LadyaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LadyaNodeRepository.ensureInitialized(applicationContext)
        AppNotificationManager.ensureChannels(applicationContext)
        requestNotificationPermissionIfNeeded()
        handleIntent(intent)
        LadyaForegroundService.start(this)

        setContent {
            LadyaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        LadyaNodeRepository.setAppForeground(true)
    }

    override fun onStop() {
        LadyaNodeRepository.setAppForeground(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_OPEN_ROUTE)) {
            AppRoute.Call.name -> {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                LadyaNodeRepository.requestOpenRoute(AppRoute.Call)
            }
            AppRoute.Chat.name -> {
                LadyaNodeRepository.requestOpenRoute(AppRoute.Chat)
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(permission), 1001)
            }
        }
    }

    companion object {
        private const val EXTRA_OPEN_ROUTE = "open_route"

        fun createOpenCallIntent(context: Context, fromNotification: Boolean = false): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_ROUTE, AppRoute.Call.name)
                putExtra("from_notification", fromNotification)
            }
        }

        fun createIncomingCallFullscreenIntent(context: Context): Intent {
            return createOpenCallIntent(context, fromNotification = true).apply {
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }

        fun createOpenChatIntent(context: Context, fromNotification: Boolean = false): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_ROUTE, AppRoute.Chat.name)
                putExtra("from_notification", fromNotification)
            }
        }
    }
}
