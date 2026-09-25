package com.example.pulsar.playback.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * USB 独占设备生命周期控制器，同时作为 [LibusbAudioSink] 的驱动门面。
 *
 * 设备句柄只在设备级 open/close 时创建和销毁；切歌、暂停和换采样率只操作流级
 * start/stop/flush，不重新 claim 接口。
 */
class UsbExclusiveController(context: Context) : UsbExclusiveDriver {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val stateMachine = UsbExclusiveStateMachine()
    private val nativeLock = Any()
    private val _state = MutableStateFlow<UsbExclusiveState>(UsbExclusiveState.Disabled)
    val state: StateFlow<UsbExclusiveState> = _state

    private var connection: UsbDeviceConnection? = null
    private var handle: Long = 0L
    private var selectedFormat: UsbAudioFormat? = null
    /** 设备描述符解析出的全部可用格式，startStream 时按位深/采样率/声道协商。 */
    private var availableFormats: List<UsbAudioFormat> = emptyList()
    private var streamParams: NativeStreamParams? = null
    private var currentSampleRate: Int? = null
    private var writtenBytes = 0L
    private var playedBase = 0L
    private var streaming = false
    private var enabled = false
    private var lifecycleGeneration = 0L
    private var selectedDeviceKey: String? = null
    private var openedDeviceKey: String? = null
    private var volumePercent: Int = 100
    private var hardwareVolumeRange: IntArray? = null

    override val isOpen: Boolean
        get() = synchronized(nativeLock) { handle != 0L }
    override val isStreaming: Boolean
        get() = synchronized(nativeLock) { streaming }
    override val format: UsbAudioFormat?
        get() = synchronized(nativeLock) { selectedFormat }
    override val isHighSpeed: Boolean
        // 链路速度由 native 在 open() 时用 libusb_get_device_speed 探测——这是唯一
        // 可靠来源（UsbDeviceConnection 在低 minSdk 不暴露速度）。按全速把 8 倍数据
        // 塞进高速微帧会导致等时端点掉线，故必须用真实速度推导每包帧数。
        get() = synchronized(nativeLock) {
            if (handle != 0L) UsbNativeDriver.nativeIsHighSpeed(handle) else false
        }
    override val sampleRate: Int?
        get() = synchronized(nativeLock) { currentSampleRate }
    val hardwareVolume: IntArray?
        get() = synchronized(nativeLock) { hardwareVolumeRange?.copyOf() }

    private var selectedDevice: UsbDevice? = null

    private val permissionAction = "${appContext.packageName}.USB_PERMISSION"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                permissionAction -> {
                    val device = intent.usbDeviceExtra() ?: return
                    if (!enabled || device.deviceName != selectedDeviceKey) return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        setState(stateMachine.onPermissionResult(true))
                        openDevice(device, lifecycleGeneration)
                    } else {
                        setState(stateMachine.onPermissionResult(false))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.usbDeviceExtra() ?: return
                    if (enabled && isAudioDevice(device)) discover(device)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.usbDeviceExtra() ?: return
                    if (device.deviceName == selectedDeviceKey ||
                        device.deviceName == openedDeviceKey
                    ) closeDevice()
                }
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(permissionAction)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    fun enable() {
        val device: UsbDevice?
        synchronized(nativeLock) {
            if (enabled) return
            enabled = true
            lifecycleGeneration++
            device = usbManager.deviceList.values.firstOrNull(::isAudioDevice)
        }
        if (device == null) {
            setState(stateMachine.onEnable(false))
        } else {
            setState(stateMachine.onEnable(true))
            discover(device)
        }
    }

    fun disable() {
        synchronized(nativeLock) {
            enabled = false
            lifecycleGeneration++
            selectedDevice = null
            selectedDeviceKey = null
            closeNativeDevice()
        }
        setState(stateMachine.onDisable())
    }

    fun release() {
        disable()
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    override fun playedFrames(): Long = synchronized(nativeLock) {
        if (handle != 0L) {
            (UsbNativeDriver.nativeGetPlayedFrames(handle) - playedBase).coerceAtLeast(0L)
        } else 0L
    }

    override fun heartbeat(): Long = synchronized(nativeLock) {
        if (handle != 0L) UsbNativeDriver.nativeGetHeartbeat(handle) else 0L
    }

    override fun loopExited(): Boolean = synchronized(nativeLock) {
        // 句柄为 0 = 设备已关闭，泵必然不再运行 → 报 true 让看门狗立即回退，
        // 不必再等 6 秒饥饿硬上限（拔线后应尽快切回系统音频继续放）。
        handle == 0L || UsbNativeDriver.nativeIsLoopExited(handle)
    }

    override fun underruns(): Long = synchronized(nativeLock) {
        if (handle != 0L) UsbNativeDriver.nativeGetUnderruns(handle) else 0L
    }

    override fun pendingFrames(): Long = synchronized(nativeLock) {
        val frameBytes = selectedFormat?.frameBytes() ?: return@synchronized 0L
        val acceptedFrames = writtenBytes / frameBytes
        val played = if (handle != 0L) {
            (UsbNativeDriver.nativeGetPlayedFrames(handle) - playedBase).coerceAtLeast(0L)
        } else 0L
        (acceptedFrames - played).coerceAtLeast(0L)
    }

    override fun write(bytes: ByteArray): Int = synchronized(nativeLock) {
        if (handle == 0L || !streaming) return@synchronized 0
        val frameBytes = selectedFormat?.frameBytes() ?: return@synchronized 0
        val completeLength = bytes.size - (bytes.size % frameBytes)
        if (completeLength <= 0) return@synchronized 0
        val accepted = UsbNativeDriver.nativeWrite(handle, bytes, 0, completeLength)
            .coerceAtLeast(0)
        val completeAccepted = accepted - (accepted % frameBytes)
        writtenBytes += completeAccepted
        completeAccepted
    }

    override fun flushRing() = synchronized(nativeLock) {
        if (handle != 0L) {
            UsbNativeDriver.nativeFlush(handle)
            writtenBytes = 0L
            playedBase = UsbNativeDriver.nativeGetPlayedFrames(handle)
        }
    }

    override fun startStream(sampleRate: Int, bitsPerSample: Int, channels: Int): Int = synchronized(nativeLock) {
        if (handle == 0L) return@synchronized UsbNativeDriver.Error.INIT.code
        // 同参数复用：切歌（新媒体源重新 configure → engage）时采样率/位深/声道若与
        // 当前流完全一致，重启泵毫无必要——native start_stream() 会重置 ring 读写
        // 指针，把旧曲未播的尾部（ring ≈1s + 120ms 在途）整段丢弃，听感即"歌尾被
        // 切"。此处直接复用现有流：旧曲尾部播完，新曲数据无缝续上（与系统
        // AudioTrack 跨媒体项保留缓冲的行为一致）。仅格式变化时才真正重启。
        if (streaming && currentSampleRate == sampleRate &&
            selectedFormat?.bitsPerSample == bitsPerSample &&
            selectedFormat?.channels == channels
        ) {
            Log.i(TAG, "USB stream reuse: 同格式切歌复用现有流 rate=$sampleRate")
            return@synchronized 0
        }
        val format = negotiateFormat(sampleRate, bitsPerSample, channels)
        if (format == null) {
            Log.w(
                TAG,
                "USB stream start: 无匹配格式 rate=$sampleRate bits=$bitsPerSample ch=$channels, " +
                    "available=${availableFormats.map { "b${it.bitsPerSample}@${it.sampleRates}" }}",
            )
            return@synchronized UsbNativeDriver.Error.INIT.code
        }
        selectedFormat = format
        val highSpeed = isHighSpeed
        val params = UsbStreamParams.derive(format, sampleRate, highSpeed)
        Log.i(
            TAG,
            "USB stream start: requestedRate=$sampleRate, selectedChannels=${format.channels}, " +
                "selectedBits=${format.bitsPerSample}, alt=${format.altSetting}, " +
                "clockId=${format.clockId}, controlInterface=${format.controlInterfaceNumber}, " +
                "highSpeed=$highSpeed, ticks=${params.ticksPerPacket}, " +
                "framesPerUframeQ16=${params.framesPerUframeQ16}, " +
                "bytesPerFrame=${params.bytesPerFrame}, maxPacket=${format.maxPacketSize}",
        )
        val result = UsbNativeDriver.nativeStartStream(
            handle = handle,
            sampleRate = sampleRate,
            ticksPerPacket = params.ticksPerPacket,
            bytesPerFrame = params.bytesPerFrame,
            framesPerUframeQ16 = params.framesPerUframeQ16,
            maxPacketSize = format.maxPacketSize,
            altSetting = format.altSetting,
        )
        if (result == 0) {
            streamParams = params
            currentSampleRate = sampleRate
            writtenBytes = 0L
            playedBase = UsbNativeDriver.nativeGetPlayedFrames(handle)
            streaming = true
            setState(stateMachine.onStreamingStarted())
        } else {
            streaming = false
            setState(stateMachine.onError("start stream failed: $result"))
        }
        result
    }

    /**
     * 在设备可用格式里按 (采样率, 位深, 声道) 协商格式。位深与声道必须精确匹配——
     * 不一致时 ExoPlayer 帧宽与 USB 帧宽错位，从第一帧就失真（engage 日志有对照诊断）。
     * 采样率由 [UsbFormatNegotiator] 打分兜底：支持则优先，不支持由 native SET_CUR
     * 就近生效。返回 null 表示设备无法按该规格输出，由 sink 回退系统音频。
     */
    private fun negotiateFormat(sampleRate: Int, bitsPerSample: Int, channels: Int): UsbAudioFormat? {
        if (availableFormats.isEmpty()) {
            val current = selectedFormat ?: return null
            return current.takeIf { it.bitsPerSample == bitsPerSample && it.channels == channels }
        }
        val candidate = UsbFormatNegotiator.negotiate(availableFormats, sampleRate, bitsPerSample, channels)
            ?: return null
        // negotiate 打分里采样率权重最高（+2），可能选出一个位深/声道不匹配的格式，必须校验。
        return candidate.takeIf { it.bitsPerSample == bitsPerSample && it.channels == channels }
    }

    override fun stopStream() = synchronized(nativeLock) {
        if (handle == 0L || !streaming) return@synchronized
        UsbNativeDriver.nativeStopStream(handle)
        streaming = false
        setState(stateMachine.onStreamingStopped())
    }

    override fun setVolume(percent: Int) = synchronized(nativeLock) {
        volumePercent = percent
        applyVolumeLocked(percent)
    }

    override fun setMuted(muted: Boolean) = synchronized(nativeLock) {
        if (handle != 0L) UsbNativeDriver.nativeSetMuted(handle, muted)
    }

    /**
     * 调用方必须持有 [nativeLock]。硬件声明了可写 Feature Unit 时优先发送真正的
     * UAC2 SET_CUR 控制传输（[sendHardwareVolume]，单位是 dB，本身对数量纲，不需要
     * 额外套听感曲线），成功后 native 侧固定 100（原样直送），避免硬件衰减之上再
     * 叠加一层软件衰减；没有硬件音量能力或发送失败则退回 native 软件线性衰减——
     * 这条路径先经过 [UsbVolumeCurve] 转成听感曲线再传给 native，否则线性幅度
     * 衰减跟人耳响度感知不匹配，真机验证发现日常听感音量会被压缩到滑块 1%-2%
     * 这一段，分辨率差到几乎调不准。
     */
    private fun applyVolumeLocked(percent: Int) {
        if (handle == 0L) return
        val conn = connection
        val format = selectedFormat
        val range = hardwareVolumeRange
        if (conn != null && format != null && format.hasHardwareVolume && range != null) {
            val ok = sendHardwareVolume(conn, format, range, percent)
            if (ok) {
                UsbNativeDriver.nativeSetVolume(handle, 100)
                return
            }
            Log.w(TAG, "硬件音量 SET_CUR 发送失败，退回软件衰减")
        }
        UsbNativeDriver.nativeSetVolume(handle, UsbVolumeCurve.apply(percent))
    }

    /** 发送 UAC2 Feature Unit 主音量 SET_CUR 控制传输，返回是否完整送达。 */
    private fun sendHardwareVolume(
        conn: UsbDeviceConnection,
        format: UsbAudioFormat,
        range: IntArray,
        percent: Int,
    ): Boolean {
        val db256 = UsbHardwareVolumeControl.percentToDb256(
            percent, minDb256 = range[0], maxDb256 = range[1], stepDb256 = range[2],
        )
        val payload = UsbHardwareVolumeControl.db256ToPayload(db256)
        val mask = if ((format.volumeChannelMask and 1) != 0) 1 else format.volumeChannelMask
        for (channel in 0 until Int.SIZE_BITS) {
            if ((mask and (1 shl channel)) == 0) continue
            val sent = conn.controlTransfer(
                UsbHardwareVolumeControl.REQUEST_TYPE_SET_CUR,
                UsbHardwareVolumeControl.REQUEST_SET_CUR,
                UsbHardwareVolumeControl.wValue(channel),
                UsbHardwareVolumeControl.wIndex(format.featureUnitId, format.controlInterfaceNumber),
                payload,
                payload.size,
                CONTROL_TRANSFER_TIMEOUT_MS,
            )
            if (sent != payload.size) {
                Log.w(TAG, "USB hardware volume SET_CUR failed: channel=$channel sent=$sent expected=${payload.size}")
                return false
            }
        }
        return mask != 0
    }

    private fun discover(device: UsbDevice) {
        val generation: Long
        synchronized(nativeLock) {
            if (!enabled) return
            generation = ++lifecycleGeneration
            selectedDevice = device
            selectedDeviceKey = device.deviceName
            closeNativeDevice()
        }
        setState(stateMachine.onDeviceReplaced())
        if (!usbManager.hasPermission(device)) {
            requestPermission(device, generation)
        } else {
            openDevice(device, generation)
        }
    }

    private fun requestPermission(device: UsbDevice, generation: Long) {
        synchronized(nativeLock) {
            if (!enabled || generation != lifecycleGeneration ||
                device.deviceName != selectedDeviceKey
            ) return
        }
        setState(stateMachine.onEnable(true))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(permissionAction).setPackage(appContext.packageName),
            flags
        )
        usbManager.requestPermission(device, pendingIntent)
    }

    private fun openDevice(device: UsbDevice, generation: Long) {
        synchronized(nativeLock) {
            if (!enabled || generation != lifecycleGeneration ||
                device.deviceName != selectedDeviceKey
            ) return
        }
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            setState(stateMachine.onError("open USB connection failed"))
            return
        }
        val formats = runCatching { UsbDescriptorParser.parse(conn.rawDescriptors) }.getOrElse {
            conn.close()
            setState(stateMachine.onError("parse USB descriptor failed"))
            return
        }
        val chosen = formats.firstOrNull()
        if (chosen == null) {
            conn.close()
            setState(stateMachine.onError("no USB audio output format"))
            return
        }
        availableFormats = formats
        Log.i(
            TAG,
            "USB format selected: uac=${chosen.uacVersion}, interface=${chosen.interfaceNumber}, " +
                "alt=${chosen.altSetting}, endpoint=0x${chosen.endpointAddress.toString(16)}, " +
                "channels=${chosen.channels}, bits=${chosen.bitsPerSample}, " +
                "subframe=${chosen.subframeSize}, maxPacket=${chosen.maxPacketSize}, " +
                "rates=${chosen.sampleRates}",
        )
        val opened = synchronized(nativeLock) {
            if (!enabled || generation != lifecycleGeneration ||
                device.deviceName != selectedDeviceKey
            ) return@synchronized 0L
            UsbNativeDriver.nativeOpen(
                fd = conn.fileDescriptor,
                interfaceNumber = chosen.interfaceNumber,
                altSetting = chosen.altSetting,
                endpointAddress = chosen.endpointAddress,
                uacVersion = chosen.uacVersion,
                clockId = chosen.clockId,
                controlInterfaceNumber = chosen.controlInterfaceNumber,
                featureUnitId = chosen.featureUnitId,
                volumeChannelMask = chosen.volumeChannelMask,
            )
        }
        if (opened in UsbNativeDriver.Error.SUBMIT.code.toLong()..UsbNativeDriver.Error.INIT.code.toLong()) {
            conn.close()
            setState(stateMachine.onError("native USB open failed: $opened"))
            return
        }
        if (opened == 0L) {
            conn.close()
            setState(stateMachine.onError("native USB open returned an invalid handle"))
            return
        }
        synchronized(nativeLock) {
            val current = enabled && generation == lifecycleGeneration &&
                device.deviceName == selectedDeviceKey
            if (!current) {
                UsbNativeDriver.nativeClose(opened)
                conn.close()
                return
            }
            closeNativeDevice()
            connection = conn
            handle = opened
            // closeNativeDevice() 会清理旧设备的格式；新句柄建立后必须恢复本次解析出的
            // 全部格式，否则 startStream 只能看到 selectedFormat（通常是第一个 16bit），
            // 永远无法协商后续的 24/32bit alt setting。
            availableFormats = formats
            selectedFormat = chosen
            openedDeviceKey = device.deviceName
            hardwareVolumeRange = UsbNativeDriver.nativeGetHardwareVolumeRange(handle)
            Log.i(
                TAG,
                "USB hardware volume: descriptor=${chosen.hasHardwareVolume}, " +
                    "range=${hardwareVolumeRange?.joinToString(prefix = "[", postfix = "]") ?: "unsupported"}",
            )
            // 打开新句柄后立即应用当前音量：硬件音量可用时走 SET_CUR，否则软件衰减。
            applyVolumeLocked(volumePercent)
        }
        setState(stateMachine.onOpenResult(true))
    }

    /** 关闭当前设备资源，但不触发设备状态迁移。调用方必须持有 [nativeLock]。 */
    private fun closeNativeDevice() {
        if (handle != 0L) {
            if (streaming) UsbNativeDriver.nativeStopStream(handle)
            streaming = false
            UsbNativeDriver.nativeClose(handle)
            handle = 0L
        }
        connection?.close()
        connection = null
        selectedFormat = null
        availableFormats = emptyList()
        hardwareVolumeRange = null
        currentSampleRate = null
        streamParams = null
        openedDeviceKey = null
        writtenBytes = 0L
        playedBase = 0L
    }

    private fun closeDevice() {
        synchronized(nativeLock) {
            lifecycleGeneration++
            closeNativeDevice()
            selectedDevice = null
            selectedDeviceKey = null
        }
        if (enabled) setState(stateMachine.onDeviceDetached())
    }

    private fun setState(next: UsbExclusiveState) {
        _state.value = next
    }

    private fun isAudioDevice(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any {
            val intf = device.getInterface(it)
            intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                intf.interfaceSubclass == AUDIO_STREAMING_SUBCLASS
        }

    private fun UsbAudioFormat.frameBytes(): Int =
        (if (subframeSize > 0) subframeSize else (bitsPerSample + 7) / 8) * channels

    @Suppress("DEPRECATION")
    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private companion object {
        const val TAG = "UsbExclusiveController"
        const val AUDIO_STREAMING_SUBCLASS = 0x02
        const val CONTROL_TRANSFER_TIMEOUT_MS = 1000
    }
}
