package nullvp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import nullvp.core.AndroidCoreBridge
import nullvp.core.androidEngineInstance
import nullvp.core.initializeAndroidCoreEngine
import nullvp.domain.model.Profile

class MainActivity : ComponentActivity() {

    companion object {
        var instance: MainActivity? = null
        var vpnPermissionLauncher: ActivityResultLauncher<Intent>? = null
        var pendingProfile: Profile? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        initializeAndroidCoreEngine(this)
        nullvp.platform.ClipboardHelper.setContext(this)
        nullvp.platform.StorageHelper.setContext(this)
        nullvp.util.AndroidSecurityHelper.ensureInitialized(nullvp.data.store.AppStateStore.settings.value)

        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                pendingProfile?.let { profile ->
                    pendingProfile = null
                    androidEngineInstance.start(profile)
                }
            } else {
                pendingProfile = null
                AndroidCoreBridge.setError("VPN permission denied by user")
            }
        }

        setContent {
            App()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
            vpnPermissionLauncher = null
        }
    }
}
