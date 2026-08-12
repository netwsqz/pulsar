package com.example.pulsar.playback.usb

/**
 * 等时传输（isochronous）时序的纯数学，与 USB 协议字节解耦，便于单元测试。
 *
 * 背景：USB 音频数据走等时端点，每个微帧（微帧）传输若干帧 PCM。
 * - 高速（HS）每 125µs 一个微帧 → 每秒 8000 微帧
 * - 全速（FS）每 1ms 一帧 → 每秒 1000 帧
 *
 * 当采样率不能整除微帧率时（如 44.1kHz / 8000 = 5.5125 帧/微帧），
 * 用 [IsoFrameAccumulator] 的 16.16 定点分数累加让每包帧数在整数之间
 * 交替，长期零漂移 —— 否则设备 FIFO 会因节奏偏差溢出/欠载。
 */
object UsbIsoScheduler {

    /** 高速链路每秒的微帧数（125µs/微帧）。 */
    private const val HIGH_SPEED_UFRAMES_PER_SEC = 8_000

    /** 全速链路每秒的微帧数（1ms/帧）。 */
    private const val FULL_SPEED_UFRAMES_PER_SEC = 1_000

    /** 16.16 定点的整数 1。 */
    private const val Q16_UNIT = 65_536L

    /**
     * 每秒微帧数：高速 8000，全速 1000。
     */
    fun microframesPerSec(isHighSpeed: Boolean): Int =
        if (isHighSpeed) HIGH_SPEED_UFRAMES_PER_SEC else FULL_SPEED_UFRAMES_PER_SEC

    /**
     * 每微帧应发出的平均帧数的 16.16 定点表示。
     *
     * = sampleRate * 65536 / microframesPerSec。
     * 用 Long 运算，避免 192kHz 等采样率乘 65536 时 Int 溢出。
     */
    fun framesPerMicroframeQ16(sampleRate: Int, microframesPerSec: Int): Long =
        sampleRate.toLong() * Q16_UNIT / microframesPerSec

    /**
     * 单个等时包最多能装多少帧 PCM。
     *
     * = maxPacketSize / bytesPerFrame；任一参数非正时返回 0。
     */
    fun maxFramesPerPacket(maxPacketSize: Int, bytesPerFrame: Int): Int =
        if (maxPacketSize <= 0 || bytesPerFrame <= 0) 0 else maxPacketSize / bytesPerFrame

    /**
     * 等时包之间的间隔，以「累积器刻度」计（IsoFrameAccumulator 的累加次数）。
     *
     * 高速：2^(bInterval-1) 微帧（bInterval=1 → 每 125µs，bInterval=4 → 每 1ms）。
     * 全速：bInterval 帧（每帧 1ms）——注意不是 bInterval*1000，因为全速的
     * 累积器刻度是 1ms（microframesPerSec=1000），刻度数直接就是 bInterval。
     * 非法 bInterval（<=0）返回 1（保守最密）。
     */
    fun uframesPerPacketInterval(bInterval: Int, isHighSpeed: Boolean): Int =
        when {
            bInterval <= 0 -> 1
            isHighSpeed -> 1 shl (bInterval - 1)
            else -> bInterval
        }
}

/**
 * 16.16 定点分数累加器（Bresenham 风格）。
 *
 * 每次调用 [nextFrameCount] 把固定的每微帧帧数（q16）累加进 [acc]，
 * 取出整数部分作为本微帧应发的帧数，余数留待下次。长期输出帧数严格
 * 等于输入采样率（零漂移），高采样率一次返回多帧、低采样率返回 0 跳包。
 *
 * 单生产者单消费者语义，无锁；只从音频线程调用。
 */
class IsoFrameAccumulator(private val framesPerMicroframeQ16: Long) {

    private var acc: Long = 0L

    /**
     * 本微帧应发送的帧数。
     *
     * 返回 0 = 本微帧不发包（低采样率跳包）；返回 n = 发 n 帧的包
     * （高采样率一包多帧）。平均每微帧发出的帧数为
     * framesPerMicroframeQ16 / 65536，长期无漂移。
     */
    fun nextFrameCount(): Long {
        acc += framesPerMicroframeQ16
        if (acc >= 65_536L) {
            val frames = acc / 65_536L
            acc -= frames * 65_536L
            return frames
        }
        return 0L
    }
}
