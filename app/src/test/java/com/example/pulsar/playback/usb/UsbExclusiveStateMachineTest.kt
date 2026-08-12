package com.example.pulsar.playback.usb

import com.example.pulsar.playback.usb.UsbExclusiveState.AwaitingPermission
import com.example.pulsar.playback.usb.UsbExclusiveState.Disabled
import com.example.pulsar.playback.usb.UsbExclusiveState.Error
import com.example.pulsar.playback.usb.UsbExclusiveState.NoDevice
import com.example.pulsar.playback.usb.UsbExclusiveState.Ready
import com.example.pulsar.playback.usb.UsbExclusiveState.Streaming
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [UsbExclusiveStateMachine] 的单元测试。
 *
 * 表驱动：每条用例一行（起点状态 / 事件 / 期望状态），覆盖迁移表每一行
 * 有效迁移与代表性无效迁移；再补三条完整生命周期的行为测试。
 */
class UsbExclusiveStateMachineTest {

    /** 表驱动用例。[action] 对机器施加一个事件并返回该事件的返回值。 */
    private data class Case(
        val description: String,
        val start: UsbExclusiveState,
        val action: (UsbExclusiveStateMachine) -> UsbExclusiveState,
        val expected: UsbExclusiveState,
    )

    /** 通过合法迁移把一台新机器驱动到 [target]，作为用例起点。 */
    private fun driveTo(target: UsbExclusiveState): UsbExclusiveStateMachine {
        val machine = UsbExclusiveStateMachine()
        when (target) {
            Disabled -> Unit // 初始状态就是 Disabled
            NoDevice -> machine.onEnable(false)
            AwaitingPermission -> machine.onEnable(true)
            Ready -> {
                machine.onEnable(true)
                machine.onPermissionResult(true)
            }
            Streaming -> {
                machine.onEnable(true)
                machine.onPermissionResult(true)
                machine.onStreamingStarted()
            }
            is Error -> {
                machine.onEnable(true)
                machine.onPermissionResult(true)
                machine.onOpenResult(false) // 唯一路径：产生 Error("open failed")
            }
        }
        assertEquals("driveTo 应到达目标状态", target, machine.state)
        return machine
    }

    @Test
    fun `迁移表全部有效迁移得到正确新状态`() {
        val cases = listOf(
            Case("Disabled 开启且发现设备", Disabled, { it.onEnable(true) }, AwaitingPermission),
            Case("Disabled 开启但无设备", Disabled, { it.onEnable(false) }, NoDevice),
            Case("NoDevice 设备接入", NoDevice, { it.onDeviceAttached() }, AwaitingPermission),
            Case("NoDevice 重新开启且发现设备", NoDevice, { it.onEnable(true) }, AwaitingPermission),
            Case("NoDevice 关闭独占", NoDevice, { it.onDisable() }, Disabled),
            Case("AwaitingPermission 授权成功", AwaitingPermission, { it.onPermissionResult(true) }, Ready),
            Case("AwaitingPermission 授权被拒保持等待", AwaitingPermission, { it.onPermissionResult(false) }, AwaitingPermission),
            Case("AwaitingPermission 设备拔出", AwaitingPermission, { it.onDeviceDetached() }, NoDevice),
            Case("AwaitingPermission 关闭独占", AwaitingPermission, { it.onDisable() }, Disabled),
            Case("AwaitingPermission 出错", AwaitingPermission, { it.onError("boom") }, Error("boom")),
            Case("Ready 打开句柄失败", Ready, { it.onOpenResult(false) }, Error("open failed")),
            Case("Ready 开始流", Ready, { it.onStreamingStarted() }, Streaming),
            Case("Ready 设备拔出", Ready, { it.onDeviceDetached() }, NoDevice),
            Case("Ready 关闭独占", Ready, { it.onDisable() }, Disabled),
            Case("Ready 出错", Ready, { it.onError("boom") }, Error("boom")),
            Case("Streaming 停止流", Streaming, { it.onStreamingStopped() }, Ready),
            Case("Streaming 设备拔出", Streaming, { it.onDeviceDetached() }, NoDevice),
            Case("Streaming 关闭独占", Streaming, { it.onDisable() }, Disabled),
            Case("Streaming 出错", Streaming, { it.onError("boom") }, Error("boom")),
            Case("Error 重试且设备接入", Error("open failed"), { it.onDeviceAttached() }, AwaitingPermission),
            Case("Error 重试但有设备", Error("open failed"), { it.onEnable(true) }, AwaitingPermission),
            Case("Error 重试但无设备", Error("open failed"), { it.onEnable(false) }, NoDevice),
            Case("Error 关闭独占", Error("open failed"), { it.onDisable() }, Disabled),
            Case("Error 开始流成功恢复", Error("open failed"), { it.onStreamingStarted() }, Streaming),
            Case("Error 拔出设备回到无设备", Error("open failed"), { it.onDeviceDetached() }, NoDevice),
            Case("Ready 更换设备等待授权", Ready, { it.onDeviceReplaced() }, AwaitingPermission),
            Case("Streaming 更换设备等待授权", Streaming, { it.onDeviceReplaced() }, AwaitingPermission),
            Case("NoDevice 更换设备等待授权", NoDevice, { it.onDeviceReplaced() }, AwaitingPermission),
            Case("AwaitingPermission 更换设备保持等待授权", AwaitingPermission, { it.onDeviceReplaced() }, AwaitingPermission),
            Case("Error 更换设备等待授权", Error("open failed"), { it.onDeviceReplaced() }, AwaitingPermission),
        )

        cases.forEach { c ->
            val machine = driveTo(c.start)
            val result = c.action(machine)
            assertEquals(c.description + "：返回值应为新状态", c.expected, result)
            assertEquals(c.description + "：state 与返回值一致", result, machine.state)
        }
    }

    @Test
    fun `无效迁移保持原状态且 state 与返回值一致`() {
        val cases = listOf(
            Case("Disabled 收到设备接入事件", Disabled, { it.onDeviceAttached() }, Disabled),
            Case("Disabled 收到设备拔出事件", Disabled, { it.onDeviceDetached() }, Disabled),
            Case("Disabled 收到授权结果", Disabled, { it.onPermissionResult(true) }, Disabled),
            Case("Disabled 收到打开结果", Disabled, { it.onOpenResult(true) }, Disabled),
            Case("Disabled 收到开始流事件", Disabled, { it.onStreamingStarted() }, Disabled),
            Case("Disabled 收到停止流事件", Disabled, { it.onStreamingStopped() }, Disabled),
            Case("Disabled 收到错误事件", Disabled, { it.onError("boom") }, Disabled),
            Case("NoDevice 收到开启但无设备事件", NoDevice, { it.onEnable(false) }, NoDevice),
            Case("NoDevice 收到设备拔出事件", NoDevice, { it.onDeviceDetached() }, NoDevice),
            Case("NoDevice 收到授权结果", NoDevice, { it.onPermissionResult(true) }, NoDevice),
            Case("NoDevice 收到打开结果", NoDevice, { it.onOpenResult(true) }, NoDevice),
            Case("NoDevice 收到开始流事件", NoDevice, { it.onStreamingStarted() }, NoDevice),
            Case("NoDevice 收到错误事件", NoDevice, { it.onError("boom") }, NoDevice),
            Case("AwaitingPermission 收到开启事件", AwaitingPermission, { it.onEnable(true) }, AwaitingPermission),
            Case("AwaitingPermission 收到设备接入事件", AwaitingPermission, { it.onDeviceAttached() }, AwaitingPermission),
            Case("AwaitingPermission 收到打开结果", AwaitingPermission, { it.onOpenResult(true) }, AwaitingPermission),
            Case("AwaitingPermission 收到开始流事件", AwaitingPermission, { it.onStreamingStarted() }, AwaitingPermission),
            Case("AwaitingPermission 收到停止流事件", AwaitingPermission, { it.onStreamingStopped() }, AwaitingPermission),
            Case("Ready 收到开启事件", Ready, { it.onEnable(true) }, Ready),
            Case("Ready 收到设备接入事件", Ready, { it.onDeviceAttached() }, Ready),
            Case("Ready 收到授权结果", Ready, { it.onPermissionResult(true) }, Ready),
            Case("Ready 打开成功保持在 Ready", Ready, { it.onOpenResult(true) }, Ready),
            Case("Ready 收到停止流事件", Ready, { it.onStreamingStopped() }, Ready),
            Case("Streaming 收到设备接入事件后切换等待授权", Streaming, { it.onDeviceAttached() }, AwaitingPermission),
            Case("Streaming 收到开启事件", Streaming, { it.onEnable(true) }, Streaming),
            Case("Streaming 收到授权结果", Streaming, { it.onPermissionResult(true) }, Streaming),
            Case("Streaming 收到打开结果", Streaming, { it.onOpenResult(true) }, Streaming),
            Case("Streaming 收到开始流事件", Streaming, { it.onStreamingStarted() }, Streaming),
            Case("Disabled 收到更换设备事件", Disabled, { it.onDeviceReplaced() }, Disabled),
            Case("Error 收到授权结果", Error("open failed"), { it.onPermissionResult(true) }, Error("open failed")),
            Case("Error 收到打开结果", Error("open failed"), { it.onOpenResult(true) }, Error("open failed")),
            Case("Error 收到错误事件", Error("open failed"), { it.onError("boom") }, Error("open failed")),
        )

        cases.forEach { c ->
            val machine = driveTo(c.start)
            val result = c.action(machine)
            assertEquals(c.description + "：无效迁移应保持原状态", c.expected, result)
            assertEquals(c.description + "：state 与返回值一致", result, machine.state)
        }
    }

    @Test
    fun `完整生命周期从开启到流输出再关闭`() {
        val machine = UsbExclusiveStateMachine()

        assertEquals(AwaitingPermission, machine.onEnable(true))   // 发现设备，等待授权
        assertEquals(Ready, machine.onPermissionResult(true))      // 授权成功拿到句柄
        assertEquals(Streaming, machine.onStreamingStarted())      // 开始独占等时流
        assertEquals(Ready, machine.onStreamingStopped())          // 停止流，句柄仍持有
        assertEquals(Disabled, machine.onDisable())                // 关闭独占输出
    }

    @Test
    fun `无设备开启进入 NoDevice 后设备接入继续走授权`() {
        val machine = UsbExclusiveStateMachine()

        assertEquals(NoDevice, machine.onEnable(false))              // 开启但没有设备
        assertEquals(AwaitingPermission, machine.onDeviceAttached()) // 设备接入，等待授权
        assertEquals(Ready, machine.onPermissionResult(true))        // 授权成功
    }

    @Test
    fun `打开句柄失败进入 Error 后重试成功`() {
        val machine = UsbExclusiveStateMachine()

        machine.onEnable(true)
        machine.onPermissionResult(true)
        assertEquals(Error("open failed"), machine.onOpenResult(false)) // 打开失败
        assertEquals(AwaitingPermission, machine.onEnable(true))        // 重试：重新发现设备
        assertEquals(Ready, machine.onPermissionResult(true))           // 再次授权成功
    }
}
