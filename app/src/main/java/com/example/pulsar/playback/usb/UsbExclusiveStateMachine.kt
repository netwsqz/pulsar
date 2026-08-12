package com.example.pulsar.playback.usb

/**
 * USB 独占输出状态机。
 *
 * 管理 USB 独占输出从开启到关闭的完整生命周期，包括设备发现、权限获取、
 * 句柄打开、等时流传输与错误恢复。所有状态迁移以 [state] 属性暴露，
 * 每个 onXxx 方法返回更新后的状态。
 *
 * 纯 Kotlin，不依赖 Android API，便于单元测试。
 */
sealed interface UsbExclusiveState {

    /** 独占输出未开启。 */
    data object Disabled : UsbExclusiveState

    /** 已开启但未检测到 USB 音频设备。 */
    data object NoDevice : UsbExclusiveState

    /** 已发现 USB 音频设备，等待用户授权。 */
    data object AwaitingPermission : UsbExclusiveState

    /** 已拿到 USB 句柄（fd opened），可开始等时流。 */
    data object Ready : UsbExclusiveState

    /** 独占等时流正在输出。 */
    data object Streaming : UsbExclusiveState

    /** 错误状态，携带原因字符串。 */
    data class Error(val reason: String) : UsbExclusiveState
}

/**
 * USB 独占输出状态机。
 *
 * 初始状态：[UsbExclusiveState.Disabled]。
 * 每个事件方法做两件事：1）更新内部 [state]；2）返回更新后的 [state]。
 * 无效迁移保持原状态不变。
 */
class UsbExclusiveStateMachine {

    /** 当前状态，外部只读。 */
    var state: UsbExclusiveState = UsbExclusiveState.Disabled
        private set

    /** 开启独占输出。 */
    @Synchronized
    fun onEnable(devicePresent: Boolean): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.Disabled -> {
                if (devicePresent) UsbExclusiveState.AwaitingPermission
                else UsbExclusiveState.NoDevice
            }
            UsbExclusiveState.NoDevice -> {
                if (devicePresent) UsbExclusiveState.AwaitingPermission
                else state // 不变
            }
            UsbExclusiveState.AwaitingPermission -> state // 不变
            UsbExclusiveState.Ready -> state // 不变
            UsbExclusiveState.Streaming -> state // 不变
            is UsbExclusiveState.Error -> {
                if (devicePresent) UsbExclusiveState.AwaitingPermission
                else UsbExclusiveState.NoDevice
            }
        }
        state = next
        return next
    }

    /** 关闭独占输出。 */
    @Synchronized
    fun onDisable(): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.Disabled -> state // 不变
            else -> UsbExclusiveState.Disabled
        }
        state = next
        return next
    }

    /** USB 音频设备接入。 */
    @Synchronized
    fun onDeviceAttached(): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.NoDevice,
            is UsbExclusiveState.Error,
            UsbExclusiveState.Streaming -> UsbExclusiveState.AwaitingPermission
            else -> state // 不变
        }
        state = next
        return next
    }

    /** 设备被新发现，替换当前设备选择。 */
    @Synchronized
    fun onDeviceReplaced(): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.Disabled -> state
            else -> UsbExclusiveState.AwaitingPermission
        }
        state = next
        return next
    }

    /** USB 音频设备拔出。 */
    @Synchronized
    fun onDeviceDetached(): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.AwaitingPermission -> UsbExclusiveState.NoDevice
            UsbExclusiveState.Ready -> UsbExclusiveState.NoDevice
            UsbExclusiveState.Streaming -> UsbExclusiveState.NoDevice
            is UsbExclusiveState.Error -> UsbExclusiveState.NoDevice // 设备都拔了，错误原因不再成立
            else -> state // 不变
        }
        state = next
        return next
    }

    /** 用户授权结果。 */
    @Synchronized
    fun onPermissionResult(granted: Boolean): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.AwaitingPermission -> {
                if (granted) UsbExclusiveState.Ready
                else state // 保持等待
            }
            else -> state // 不变
        }
        state = next
        return next
    }

    /** 打开 USB 设备句柄的结果。 */
    @Synchronized
    fun onOpenResult(success: Boolean): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.Ready -> {
                if (success) state // 保持 Ready
                else UsbExclusiveState.Error("open failed")
            }
            else -> state // 不变
        }
        state = next
        return next
    }

    /** 独占等时流开始输出。 */
    @Synchronized
    fun onStreamingStarted(): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.Ready -> UsbExclusiveState.Streaming
            is UsbExclusiveState.Error -> UsbExclusiveState.Streaming // 流启动成功即从错误恢复
            else -> state // 不变
        }
        state = next
        return next
    }

    /** 独占等时流停止输出。 */
    @Synchronized
    fun onStreamingStopped(): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.Streaming -> UsbExclusiveState.Ready
            else -> state // 不变
        }
        state = next
        return next
    }

    /** 发生错误。 */
    @Synchronized
    fun onError(reason: String): UsbExclusiveState {
        val next = when (state) {
            UsbExclusiveState.AwaitingPermission -> UsbExclusiveState.Error(reason)
            UsbExclusiveState.Ready -> UsbExclusiveState.Error(reason)
            UsbExclusiveState.Streaming -> UsbExclusiveState.Error(reason)
            else -> state // 不变（Disabled / NoDevice / Error 不响应 onError）
        }
        state = next
        return next
    }
}