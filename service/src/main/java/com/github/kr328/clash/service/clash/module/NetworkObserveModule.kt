package com.github.kr328.clash.service.clash.module

import android.app.Service
import android.content.Intent
import android.net.*
import android.os.Build
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.util.asSocketAddressText
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class NetworkObserveModule(service: Service) : Module<Network?>(service) {
    private val connectivity = service.getSystemService<ConnectivityManager>()!!
    private val power = service.getSystemService<PowerManager>()
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND)
        }
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }.build()

    private data class Action(
        val type: Type,
        val network: Network,
        val dnsList: List<InetAddress> = emptyList(),
        val linkPropertiesKey: String? = null,
        val state: Boolean = false
    ) {
        enum class Type {
            Available,
            Losing,
            Lost,
            LinkPropertiesChanged,
            CapabilitiesChanged,
            BlockedStatusChanged
        }
    }

    private data class NetworkInfo(
        var ready: Boolean = false,
        var losing: Boolean = false,
        var dnsList: List<InetAddress> = emptyList(),
        var linkPropertiesKey: String? = null,
        var validated: Boolean? = null,
        var blocked: Boolean? = null
    )

    private val networkInfos = mutableMapOf<Network, NetworkInfo>()
    private val lostNetworks = linkedSetOf<Network>()
    private val callbackValidated = ConcurrentHashMap<Network, Boolean>()
    private var curDnsList = emptyList<String>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i("NetworkObserve onAvailable network=$network")
            actions.trySend(Action(Action.Type.Available, network))
        }

        override fun onLosing(network: Network, maxMsToLive: Int) {
            Log.i("NetworkObserve onLosing network=$network")
            actions.trySend(Action(Action.Type.Losing, network))
        }

        override fun onLost(network: Network) {
            Log.i("NetworkObserve onLost network=$network")
            callbackValidated.remove(network)
            actions.trySend(Action(Action.Type.Lost, network))
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            Log.i("NetworkObserve onLinkPropertiesChanged network=$network $linkProperties")
            actions.trySend(
                Action(
                    Action.Type.LinkPropertiesChanged,
                    network,
                    linkProperties.dnsServers,
                    linkProperties.key()
                )
            )
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val validated = networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
            if (callbackValidated.put(network, validated) != validated) {
                actions.trySend(
                    Action(
                        Action.Type.CapabilitiesChanged,
                        network,
                        state = validated
                    )
                )
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            actions.trySend(Action(Action.Type.BlockedStatusChanged, network, state = blocked))
        }

        override fun onUnavailable() {
            Log.i("NetworkObserve onUnavailable")
        }
    }

    private fun register(): Boolean {
        Log.i("NetworkObserve start register")
        return try {
            connectivity.registerNetworkCallback(request, callback)

            true
        } catch (e: Exception) {
            Log.w("NetworkObserve register failed", e)

            false
        }
    }

    private fun unregister(): Boolean {
        Log.i("NetworkObserve start unregister")
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w("NetworkObserve unregister failed", e)
        }

        return false
    }

    private fun networkToInt(entry: Map.Entry<Network, NetworkInfo>): Int {
        val capabilities = connectivity.getNetworkCapabilities(entry.key)
        // Lower values have higher priority; degraded links lose to validated alternatives.
        return when {
            capabilities == null -> 100
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 90
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 1
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> 2
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> 3
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 4
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE) -> 5
            // TRANSPORT_LOWPAN / TRANSPORT_THREAD / TRANSPORT_WIFI_AWARE are not for general internet access, which will not set as default route.
            else -> 20
        } + when {
            entry.value.validated == false -> 50
            entry.value.losing -> 10
            else -> 0
        }
    }

    private fun isPhysicalInternetNetwork(capabilities: NetworkCapabilities): Boolean {
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }

    private fun LinkProperties.key(): String {
        return listOf(
            interfaceName.orEmpty(),
            linkAddresses.map { it.toString() }.sorted().joinToString(),
            routes.map { it.toString() }.sorted().joinToString(),
            dnsServers.map { it.hostAddress.orEmpty() }.sorted().joinToString(),
            mtu.toString()
        ).joinToString("|")
    }

    private fun refreshNetworks() {
        try {
            val refreshed = connectivity.allNetworks.mapNotNull { network ->
                val capabilities = connectivity.getNetworkCapabilities(network)
                    ?.takeIf(::isPhysicalInternetNetwork)
                    ?: return@mapNotNull null
                val linkProperties = connectivity.getLinkProperties(network)
                    ?: return@mapNotNull null

                network to NetworkInfo(
                    ready = true,
                    dnsList = linkProperties.dnsServers,
                    linkPropertiesKey = linkProperties.key(),
                    validated = capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                )
            }.toMap()

            networkInfos.clear()
            networkInfos.putAll(refreshed)
            lostNetworks.removeAll(refreshed.keys)
        } catch (e: Exception) {
            Log.w("NetworkObserve refresh failed", e)
        }
    }

    private fun selectNetwork(): Network? {
        return networkInfos
            .asSequence()
            .filter { it.value.ready && it.value.blocked != true }
            .minByOrNull(::networkToInt)
            ?.key
    }

    private fun notifyDnsChange(network: Network?) {
        val dnsList = network
            ?.let(networkInfos::get)
            ?.dnsList
            ?.map { it.asSocketAddressText(53) }
            ?: emptyList()
        val prevDnsList = curDnsList
        if (dnsList.isNotEmpty() && prevDnsList != dnsList) {
            Log.i("notifyDnsChange $prevDnsList -> $dnsList")
            curDnsList = dnsList
            Clash.notifyDnsChanged(dnsList)
        }
    }

    private fun apply(action: Action): Boolean {
        return when (action.type) {
            Action.Type.Available -> {
                lostNetworks.remove(action.network)
                val info = networkInfos.getOrPut(action.network, ::NetworkInfo)
                val recovered = info.losing
                info.losing = false
                recovered
            }
            Action.Type.Losing -> {
                networkInfos[action.network]?.losing = true
                false
            }
            Action.Type.Lost -> {
                lostNetworks.add(action.network)
                if (lostNetworks.size > MAX_LOST_NETWORKS) {
                    lostNetworks.remove(lostNetworks.first())
                }
                networkInfos.remove(action.network)
                false
            }
            Action.Type.LinkPropertiesChanged -> {
                if (action.network in lostNetworks) {
                    false
                } else {
                    val info = networkInfos.getOrPut(action.network, ::NetworkInfo)
                    val changed =
                        info.ready && info.linkPropertiesKey != action.linkPropertiesKey
                    info.ready = true
                    info.dnsList = action.dnsList
                    info.linkPropertiesKey = action.linkPropertiesKey
                    changed
                }
            }
            Action.Type.CapabilitiesChanged -> {
                networkInfos[action.network]?.let {
                    val recovered = it.validated == false && action.state
                    it.validated = action.state
                    recovered
                } ?: false
            }
            Action.Type.BlockedStatusChanged -> {
                networkInfos[action.network]?.let {
                    val recovered = it.blocked == true && !action.state
                    it.blocked = action.state
                    recovered
                } ?: false
            }
        }
    }

    override suspend fun run() {
        val registered = register()
        val systemChanges = receiveBroadcast(false, Channel.CONFLATED) {
            // Reconcile once after Doze instead of keeping a periodic background watchdog.
            addAction(Intent.ACTION_SCREEN_ON)
            if (!registered) {
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            }
        }

        try {
            var currentNetwork: Network? = null
            var initialized = false
            var interactive = power?.isInteractive ?: true

            while (true) {
                val (action, systemAction) = select<Pair<Action?, String?>> {
                    actions.onReceive { it to null }
                    systemChanges.onReceive { null to it.action }
                }

                if (action == null) {
                    interactive = power?.isInteractive ?: true
                    if (!interactive) continue

                    val previousNetwork = currentNetwork
                    val previousLinkPropertiesKey =
                        previousNetwork?.let(networkInfos::get)?.linkPropertiesKey
                    refreshNetworks()

                    val nextNetwork = selectNetwork()
                    val nextLinkPropertiesKey =
                        nextNetwork?.let(networkInfos::get)?.linkPropertiesKey
                    val linkPropertiesChanged =
                        previousLinkPropertiesKey != nextLinkPropertiesKey
                    notifyDnsChange(nextNetwork)
                    Log.i("NetworkObserve system change $previousNetwork -> $nextNetwork")
                    currentNetwork = nextNetwork
                    val wasInitialized = initialized
                    initialized = true
                    val shouldRecover =
                        systemAction == Intent.ACTION_SCREEN_ON ||
                            !registered ||
                            (wasInitialized &&
                                (previousNetwork != nextNetwork || linkPropertiesChanged))
                    if (shouldRecover) {
                        enqueueEvent(nextNetwork)
                    }
                    continue
                }

                val recovered = apply(action)
                val nextNetwork = selectNetwork()
                interactive = power?.isInteractive ?: interactive
                // Keep state current while asleep, but defer core work until the screen is on.
                if (interactive) notifyDnsChange(nextNetwork)

                if (!initialized) {
                    currentNetwork = nextNetwork
                    initialized = true
                } else if (currentNetwork != nextNetwork) {
                    Log.i("NetworkObserve network changed $currentNetwork -> $nextNetwork")
                    currentNetwork = nextNetwork
                    if (interactive) enqueueEvent(nextNetwork)
                } else if (recovered && action.network == currentNetwork) {
                    Log.i("NetworkObserve network recovered network=$currentNetwork")
                    if (interactive) enqueueEvent(currentNetwork)
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (registered) unregister()

                Log.i("NetworkObserve dns = []")
                Clash.notifyDnsChanged(emptyList())
            }
        }
    }

    companion object {
        private const val MAX_LOST_NETWORKS = 32
    }
}
