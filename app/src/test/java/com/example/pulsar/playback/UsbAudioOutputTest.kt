package com.example.pulsar.playback

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAudioOutputTest {

    @Test
    fun `empty device list returns null`() {
        assertNull(UsbAudioOutput.findUsbOutputDeviceType(emptyList()))
    }

    @Test
    fun `usb device is found among other outputs`() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_DEVICE
        )

        assertEquals(AudioDeviceInfo.TYPE_USB_DEVICE, UsbAudioOutput.findUsbOutputDeviceType(types))
    }

    @Test
    fun `usb headset is found`() {
        assertEquals(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            UsbAudioOutput.findUsbOutputDeviceType(listOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        )
    }

    @Test
    fun `usb device takes priority over usb headset`() {
        val mixed = listOf(
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE
        )

        assertEquals(AudioDeviceInfo.TYPE_USB_DEVICE, UsbAudioOutput.findUsbOutputDeviceType(mixed))
    }

    @Test
    fun `usb device is still chosen when order varies`() {
        val mixed = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET
        )

        assertEquals(AudioDeviceInfo.TYPE_USB_DEVICE, UsbAudioOutput.findUsbOutputDeviceType(mixed))
    }

    @Test
    fun `non-usb outputs return null`() {
        val types = listOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        )

        assertNull(UsbAudioOutput.findUsbOutputDeviceType(types))
    }

    @Test
    fun `shouldRouteToUsb requires both switch and device`() {
        assertFalse(UsbAudioOutput.shouldRouteToUsb(outputEnabled = false, usbDevicePresent = true))
        assertFalse(UsbAudioOutput.shouldRouteToUsb(outputEnabled = true, usbDevicePresent = false))
        assertTrue(UsbAudioOutput.shouldRouteToUsb(outputEnabled = true, usbDevicePresent = true))
    }
}
