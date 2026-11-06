package nullvp.platform

import java.io.File

enum class RoutingMode {
    SYSTEM_PROXY, // SOCKS5/HTTP - Zero root/admin privileges needed!
    TUN_VPN       // Full network TUN adapter - Requires elevated daemon (session sudo/Wintun)
}

object DesktopPrivilegeManager {
    val isLinux: Boolean get() = System.getProperty("os.name").lowercase().contains("linux")
    val isWindows: Boolean get() = System.getProperty("os.name").lowercase().contains("windows")
    val isMac: Boolean get() = System.getProperty("os.name").lowercase().contains("mac")

    private var daemonProcess: Process? = null
    var cachedSudoPassword: String? = null
    var currentRoutingMode: RoutingMode = RoutingMode.SYSTEM_PROXY

    /**
     * Checks if the core binary already has Linux capabilities (cap_net_admin),
     * which allows running TUN without any sudo/root password at all!
     */
    fun hasNetAdminCapability(binaryPath: String): Boolean {
        if (!isLinux) return false
        return runCatching {
            val p = ProcessBuilder("getcap", binaryPath).start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            output.contains("cap_net_admin")
        }.getOrDefault(false)
    }

    /**
     * Verifies sudo password using sudo -S -k -v.
     */
    fun verifySudoPassword(password: String): Pair<Boolean, String?> {
        if (!isLinux) return true to null
        return try {
            val proc = ProcessBuilder("sudo", "-S", "-k", "-v")
                .redirectErrorStream(true)
                .start()
            proc.outputStream.bufferedWriter().use { writer ->
                writer.write("$password\n")
                writer.flush()
            }
            val exit = proc.waitFor()
            (exit == 0) to if (exit == 0) null else "Неверный пароль sudo"
        } catch (e: Exception) {
            false to (e.message ?: "Ошибка вызова sudo")
        }
    }

    /**
     * Helper command for users to grant passwordless capability once.
     */
    fun getSetcapCommand(binaryPath: String): String {
        return "sudo setcap cap_net_admin,cap_net_bind_service+ep \"$binaryPath\""
    }

    /**
     * Launches the background daemon with session privileges.
     * Uses sudo (with cached password during the desktop session) or pkexec.
     */
    fun startElevatedDaemon(
        binaryPath: String,
        socketPath: String,
        password: String? = cachedSudoPassword
    ): Result<Unit> {
        if (daemonProcess?.isAlive == true) {
            return Result.success(Unit)
        }

        return runCatching {
            if (isLinux) {
                // If has capability, run directly without sudo!
                if (hasNetAdminCapability(binaryPath)) {
                    val pb = ProcessBuilder(binaryPath, "--daemon", "--socket", socketPath)
                    daemonProcess = pb.start()
                    return@runCatching
                }

                // If password provided, feed it via stdin to sudo -S
                if (!password.isNullOrBlank()) {
                    cachedSudoPassword = password
                    val pb = ProcessBuilder("sudo", "-S", binaryPath, "--daemon", "--socket", socketPath)
                    val p = pb.start()
                    p.outputStream.bufferedWriter().use {
                        it.write(password)
                        it.newLine()
                        it.flush()
                    }
                    daemonProcess = p
                } else {
                    // Try pkexec (GUI dialog) if available
                    val pb = ProcessBuilder("pkexec", binaryPath, "--daemon", "--socket", socketPath)
                    daemonProcess = pb.start()
                }
            } else if (isWindows) {
                // On Windows: Start process with elevated token or service
                val pb = ProcessBuilder(binaryPath, "--daemon", "--socket", socketPath)
                daemonProcess = pb.start()
            }
        }
    }

    /**
     * Shuts down the background elevated daemon cleanly when user quits the app.
     */
    fun stopDaemon() {
        runCatching {
            daemonProcess?.destroy()
            daemonProcess = null
        }
    }

    // ==========================================
    // System Proxy Management (Zero Root Rights)
    // ==========================================

    fun enableSystemProxy(host: String = "127.0.0.1", port: Int = 10808) {
        if (isLinux) {
            enableLinuxProxy(host, port)
        } else if (isWindows) {
            enableWindowsProxy(host, port)
        }
    }

    fun disableSystemProxy() {
        if (isLinux) {
            disableLinuxProxy()
        } else if (isWindows) {
            disableWindowsProxy()
        }
    }

    private fun enableLinuxProxy(host: String, port: Int) {
        runCatching {
            // GNOME / Cinnamon / Mate
            ProcessBuilder("gsettings", "set", "org.gnome.system.proxy", "mode", "'manual'").start().waitFor()
            ProcessBuilder("gsettings", "set", "org.gnome.system.proxy.socks", "host", "'$host'").start().waitFor()
            ProcessBuilder("gsettings", "set", "org.gnome.system.proxy.socks", "port", port.toString()).start().waitFor()
        }
    }

    private fun disableLinuxProxy() {
        runCatching {
            ProcessBuilder("gsettings", "set", "org.gnome.system.proxy", "mode", "'none'").start().waitFor()
        }
    }

    private fun enableWindowsProxy(host: String, port: Int) {
        runCatching {
            // Set proxy in Windows Registry via reg command
            val regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
            ProcessBuilder("reg", "add", regKey, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f").start().waitFor()
            ProcessBuilder("reg", "add", regKey, "/v", "ProxyServer", "/t", "REG_SZ", "/d", "socks=$host:$port", "/f").start().waitFor()
        }
    }

    private fun disableWindowsProxy() {
        runCatching {
            val regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
            ProcessBuilder("reg", "add", regKey, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f").start().waitFor()
        }
    }
}

