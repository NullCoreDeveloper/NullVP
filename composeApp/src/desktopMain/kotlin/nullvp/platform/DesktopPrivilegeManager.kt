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

    private val hasKWriteConfig6 by lazy {
        File("/usr/bin/kwriteconfig6").exists() || checkCommand("kwriteconfig6")
    }

    private val hasKWriteConfig5 by lazy {
        File("/usr/bin/kwriteconfig5").exists() || checkCommand("kwriteconfig5")
    }

    private val hasGSettings by lazy {
        File("/usr/bin/gsettings").exists() || checkCommand("gsettings")
    }

    private val hasQdbus6 by lazy {
        File("/usr/bin/qdbus6").exists() || checkCommand("qdbus6")
    }

    private fun checkCommand(cmd: String): Boolean {
        return runCatching {
            val proc = ProcessBuilder("which", cmd).start()
            proc.waitFor() == 0
        }.getOrDefault(false)
    }

    fun enableSystemProxy(socksPort: Int = 10808, httpPort: Int = 10809, host: String = "127.0.0.1") {
        if (isLinux) {
            enableLinuxProxy(host, socksPort, httpPort)
        } else if (isWindows) {
            enableWindowsProxy(host, socksPort, httpPort)
        }
    }

    fun disableSystemProxy() {
        if (isLinux) {
            disableLinuxProxy()
        } else if (isWindows) {
            disableWindowsProxy()
        }
    }

    private fun enableLinuxProxy(host: String, socksPort: Int, httpPort: Int) {
        // 1. KDE Plasma 6 / 5 (kwriteconfig)
        if (hasKWriteConfig6 || hasKWriteConfig5) {
            runCatching {
                val kwrite = if (hasKWriteConfig6) "kwriteconfig6" else "kwriteconfig5"
                ProcessBuilder(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "1").start().waitFor()
                ProcessBuilder(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpProxy", "http://$host $httpPort").start().waitFor()
                ProcessBuilder(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "httpsProxy", "http://$host $httpPort").start().waitFor()
                ProcessBuilder(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "socksProxy", "socks://$host $socksPort").start().waitFor()
                if (hasQdbus6) {
                    ProcessBuilder("qdbus6", "org.kde.kded6", "/kded", "org.kde.kded6.reparseConfiguration", "kioslave").start().waitFor()
                }
            }
        }

        // 2. GNOME / Cinnamon / Mate (gsettings)
        if (hasGSettings) {
            runCatching {
                ProcessBuilder("gsettings", "set", "org.gnome.system.proxy", "mode", "manual").start().waitFor()
                ProcessBuilder("gsettings", "set", "org.gnome.system.proxy.http", "host", host).start().waitFor()
                ProcessBuilder("gsettings", "set", "org.gnome.system.proxy.http", "port", httpPort.toString()).start().waitFor()
                ProcessBuilder("gsettings", "set", "org.gnome.system.proxy.https", "host", host).start().waitFor()
                ProcessBuilder("gsettings", "set", "org.gnome.system.proxy.https", "port", httpPort.toString()).start().waitFor()
                ProcessBuilder("gsettings", "set", "org.gnome.system.proxy.socks", "host", host).start().waitFor()
                ProcessBuilder("gsettings", "set", "org.gnome.system.proxy.socks", "port", socksPort.toString()).start().waitFor()
            }
        }
    }

    private fun disableLinuxProxy() {
        if (hasKWriteConfig6 || hasKWriteConfig5) {
            runCatching {
                val kwrite = if (hasKWriteConfig6) "kwriteconfig6" else "kwriteconfig5"
                ProcessBuilder(kwrite, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", "ProxyType", "0").start().waitFor()
                if (hasQdbus6) {
                    ProcessBuilder("qdbus6", "org.kde.kded6", "/kded", "org.kde.kded6.reparseConfiguration", "kioslave").start().waitFor()
                }
            }
        }
        if (hasGSettings) {
            runCatching {
                ProcessBuilder("gsettings", "set", "org.gnome.system.proxy", "mode", "none").start().waitFor()
            }
        }
    }

    private fun enableWindowsProxy(host: String, socksPort: Int, httpPort: Int) {
        runCatching {
            val regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
            val proxyServer = "http=$host:$httpPort;https=$host:$httpPort;socks=$host:$socksPort"
            ProcessBuilder("reg", "add", regKey, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f").start().waitFor()
            ProcessBuilder("reg", "add", regKey, "/v", "ProxyServer", "/t", "REG_SZ", "/d", proxyServer, "/f").start().waitFor()
        }
    }

    private fun disableWindowsProxy() {
        runCatching {
            val regKey = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
            ProcessBuilder("reg", "add", regKey, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "0", "/f").start().waitFor()
        }
    }
}

