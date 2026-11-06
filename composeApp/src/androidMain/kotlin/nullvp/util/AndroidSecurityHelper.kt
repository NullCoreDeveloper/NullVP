package nullvp.util

import nullvp.data.store.AppStateStore
import nullvp.domain.model.AppSettings
import java.security.SecureRandom

object AndroidSecurityHelper {

    fun ensureInitialized(settings: AppSettings) {
        // One-time generation on first launch or if credentials are empty / default
        if (!settings.proxyAuthEnabled || settings.proxyUsername.isBlank() || settings.proxyPassword.isBlank() || settings.socksPort == 10808) {
            val random = SecureRandom()
            // Random port in range 20000..59000
            val randomPort = 20000 + random.nextInt(39000)
            val randomHttpPort = randomPort + 1

            // Cryptographically secure username
            val userBytes = ByteArray(4)
            random.nextBytes(userBytes)
            val userHex = userBytes.joinToString("") { "%02x".format(it) }
            val secureUsername = "nullvp_$userHex"

            // Cryptographically secure password (32 chars with >197 bits of entropy)
            val charset = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*-_=+"
            val passwordChars = CharArray(32) {
                charset[random.nextInt(charset.length)]
            }
            val securePassword = String(passwordChars)

            val updated = settings.copy(
                socksPort = randomPort,
                httpPort = randomHttpPort,
                proxyAuthEnabled = true,
                proxyUsername = secureUsername,
                proxyPassword = securePassword
            )
            AppStateStore.updateSettings(updated)
            AppStateStore.addLog(
                nullvp.data.store.LogLevel.INFO,
                "Security",
                "Android First Launch: Proxy security initialized. SOCKS5=$randomPort, HTTP=$randomHttpPort, User=$secureUsername"
            )
        }
    }
}

