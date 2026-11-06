package nullvp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import nullvp.core.initializeAndroidCoreEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeAndroidCoreEngine(this)
        nullvp.platform.ClipboardHelper.setContext(this)
        nullvp.platform.StorageHelper.setContext(this)
        setContent {
            App()
        }
    }
}
