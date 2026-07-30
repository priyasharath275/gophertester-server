package com.example.gophertester

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.app.ActivityCompat
import com.example.gophertester.service.ConnectionService
import com.example.gophertester.ui.tab.HomeScreen
import com.example.gophertester.ui.tab.HomeViewModel
import com.example.gophertester.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    private val vm: HomeViewModel by viewModels()

    private val allPerms: Array<String> by lazy {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            add(Manifest.permission.READ_PHONE_NUMBERS)
            add(Manifest.permission.READ_PHONE_STATE)
            add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    private val requestAllPerms =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // After user responds, start the service (it will wait for phone+internet)
            ConnectionService.startIdle(this)
            vm.onAppLaunch(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep screen on during tests
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (!hasAllPermissions()) {
            requestAllPerms.launch(allPerms)
        } else {
            // Already granted: start the service now so it auto-connects
            ConnectionService.startIdle(this)
            vm.onAppLaunch(this)
        }

        setContent {
            AppTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    HomeScreen(vm = vm)
                }
            }
        }
    }

    private fun hasAllPermissions(): Boolean =
        allPerms.all {
            ActivityCompat.checkSelfPermission(this, it) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    override fun onResume() {
        super.onResume()
        // App just came to foreground: check WS and heartbeat/reconnect as needed
        ConnectionService.poke(this)
    }
}
