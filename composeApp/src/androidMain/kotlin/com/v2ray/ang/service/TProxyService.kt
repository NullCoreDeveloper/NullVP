package com.v2ray.ang.service

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * HevSocks5Tunnel JNI interface.
 * Package must match com.v2ray.ang.service for dynamic JNI binding in libhev-socks5-tunnel.so.
 */
class TProxyService(
    private val context: Context,
    private val vpnInterface: ParcelFileDescriptor
) {
    companion object {
        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStartService(configPath: String, fd: Int)

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyStopService()

        @JvmStatic
        @Suppress("FunctionName")
        private external fun TProxyGetStats(): LongArray?

        init {
            try {
                System.loadLibrary("hev-socks5-tunnel")
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun startTun2Socks(
        socksPort: Int = 10808,
        socksUsername: String? = null,
        socksPassword: String? = null,
        mtu: Int = 1500
    ) {
        val configContent = buildConfig(socksPort, socksUsername, socksPassword, mtu)
        val configFile = File(context.filesDir, "hev-socks5-tunnel.yaml").apply {
            writeText(configContent)
        }
        try {
            TProxyStartService(configFile.absolutePath, vpnInterface.fd)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildConfig(socksPort: Int, socksUsername: String?, socksPassword: String?, mtu: Int): String {
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: $mtu")
            appendLine("  ipv4: 10.10.10.2")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            if (!socksUsername.isNullOrEmpty() && !socksPassword.isNullOrEmpty()) {
                val u = socksUsername.replace("'", "''")
                val p = socksPassword.replace("'", "''")
                appendLine("  username: '$u'")
                appendLine("  password: '$p'")
            }
            appendLine("misc:")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: warn")
        }
    }

    fun stopTun2Socks() {
        try {
            TProxyStopService()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

