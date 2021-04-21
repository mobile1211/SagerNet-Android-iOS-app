package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.RouteMode
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import io.nekohasekai.sagernet.utils.Subnet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import android.net.VpnService as BaseVpnService

class VpnService : BaseVpnService(), BaseService.Interface {

    companion object {
        private const val VPN_MTU = 1500
        private const val PRIVATE_VLAN4_CLIENT = "172.19.0.1"
        private const val PRIVATE_VLAN4_ROUTER = "172.19.0.2"
        private const val PRIVATE_VLAN6_CLIENT = "fdfe:dcba:9876::1"
        private const val PRIVATE_VLAN6_ROUTER = "fdfe:dcba:9876::2"

        private fun <T> FileDescriptor.use(block: (FileDescriptor) -> T) = try {
            block(this)
        } finally {
            try {
                Os.close(this)
            } catch (_: ErrnoException) {
            }
        }
    }

    private var conn: ParcelFileDescriptor? = null
    private var active = false
    private var metered = false

    override suspend fun startProcesses() {
        super.startProcesses()
        sendFd(startVpn())
    }

    override fun killProcesses(scope: CoroutineScope) {
        super.killProcesses(scope)
        active = false
        conn?.close()
    }


    override fun onBind(intent: Intent) = when (intent.action) {
        SERVICE_INTERFACE -> super<BaseVpnService>.onBind(intent)
        else -> super<BaseService.Interface>.onBind(intent)
    }

    override val data = BaseService.Data(this)
    override val tag = "SagerNetVpnService"
    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-vpn")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (DataStore.serviceMode == Key.MODE_VPN) {
            if (prepare(this) != null) {
                startActivity(Intent(this,
                    VpnRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else return super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        }
        stopRunner()
        return Service.START_NOT_STICKY
    }

    inner class NullConnectionException : NullPointerException(), BaseService.ExpectedException {
        override fun getLocalizedMessage() = getString(R.string.reboot_required)
    }

    private suspend fun startVpn(): FileDescriptor {
        val profile = data.proxy!!.profile
        val builder = Builder()
            .setConfigureIntent(SagerNet.configureIntent(this))
            .setSession(profile.displayName())
            .setMtu(VPN_MTU)
            .addAddress(PRIVATE_VLAN4_CLIENT, 30)
        if (DataStore.ipv6Route) {
            builder.addAddress(PRIVATE_VLAN6_CLIENT, 126)
        }

        when (DataStore.routeMode) {
            RouteMode.BYPASS_LAN, RouteMode.BYPASS_LAN_CHINA -> {
                resources.getStringArray(R.array.bypass_private_route).forEach {
                    val subnet = Subnet.fromString(it)!!
                    builder.addRoute(subnet.address.hostAddress, subnet.prefixSize)
                }
                builder.addRoute(PRIVATE_VLAN4_ROUTER, 32)
                // https://issuetracker.google.com/issues/149636790
                if (DataStore.ipv6Route) builder.addRoute("2000::", 3)
            }
            else -> {
                builder.addRoute("0.0.0.0", 0)
                if (DataStore.ipv6Route) builder.addRoute("::", 0)
            }
        }


        // https://issuetracker.google.com/issues/149636790


        /* val proxyApps = when (profile.proxyApps) {
             0 -> DataStore.proxyApps > 0
             1 -> false
             else -> true
         }
         val bypass = when (profile.proxyApps) {
             0 -> DataStore.proxyApps == 2
             3 -> true
             else -> false
         }

         if (proxyApps) {

             val me = packageName
             (profile.individual ?: DataStore.individual ?: "").split('\n')
                 .filter { it.isNotBlank() && it != me }
                 .forEach {
                     try {
                         if (bypass) builder.addDisallowedApplication(it)
                         else builder.addAllowedApplication(it)
                     } catch (ex: PackageManager.NameNotFoundException) {
    //                        Timber.w(ex)
                     }
                 }

         }
    */

        if (DataStore.enableLocalDNS) {
            builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        } else {
            builder.addDnsServer(DataStore.remoteDNS)
        }

        builder.addDisallowedApplication("com.github.shadowsocks")
        builder.addDisallowedApplication(packageName)

        metered = when (profile.meteredNetwork) {
            0 -> DataStore.meteredNetwork
            1 -> false
            else -> true
        }
        active = true   // possible race condition here?
//        builder.setUnderlyingNetworks(underlyingNetworks)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(metered)

        val conn = builder.establish() ?: throw NullConnectionException()
        this.conn = conn

        val cmd =
            arrayListOf(File(applicationInfo.nativeLibraryDir, Executable.TUN2SOCKS).canonicalPath,
                "--netif-ipaddr",
                PRIVATE_VLAN4_ROUTER,
                "--socks-server-addr",
                "127.0.0.1:${DataStore.socksPort}",
                "--tunmtu",
                VPN_MTU.toString(),
                "--sock-path",
                File(SagerNet.deviceStorage.noBackupFilesDir, "sock_path").canonicalPath,
                "--loglevel", "debug")
        if (DataStore.enableLocalDNS) {
            cmd += "--dnsgw"
            cmd += "127.0.0.1:${DataStore.localDNSPort}"
        }
        if (DataStore.ipv6Route) {
            cmd += "--netif-ip6addr"
            cmd += PRIVATE_VLAN6_ROUTER
        }
        var enableUDP = false
        when (profile.type) {
            "socks" -> enableUDP = profile.requireSOCKS().udp
        }
        cmd += "--enable-udprelay"
        data.processes!!.start(cmd, onRestartCallback = {
            try {
                sendFd(conn.fileDescriptor)
            } catch (e: ErrnoException) {
                stopRunner(false, e.message)
            }
        })
        return conn.fileDescriptor
    }

    private suspend fun sendFd(fd: FileDescriptor) {
        var tries = 0
        val path = File(SagerNet.deviceStorage.noBackupFilesDir, "sock_path").canonicalPath
        while (true) try {
            delay(50L shl tries)
            LocalSocket().use { localSocket ->
                localSocket.connect(LocalSocketAddress(path,
                    LocalSocketAddress.Namespace.FILESYSTEM))
                localSocket.setFileDescriptorsForSend(arrayOf(fd))
                localSocket.outputStream.write(42)
            }
            return
        } catch (e: IOException) {
            if (tries > 5) throw e
            tries += 1
        }
    }

    override fun onRevoke() = stopRunner()

    override fun onDestroy() {
        super.onDestroy()
        data.binder.close()
    }

}