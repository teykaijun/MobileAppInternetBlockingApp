package io.github.teykaijun.netblocker.vpn

/** What the blocking tunnel is doing right now. */
sealed interface TunnelState {
    data object Stopped : TunnelState

    data class Running(val blockedAppCount: Int) : TunnelState

    data class Failed(val reason: FailureReason) : TunnelState
}

enum class FailureReason {
    /** Another VPN app took over, or the VPN permission was removed in system settings. */
    PERMISSION_REVOKED,

    /** The system refused to create the tunnel or to run the foreground service. */
    START_FAILED,
}
