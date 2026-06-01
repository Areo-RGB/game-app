package com.example

import android.content.Context
import android.os.Build
import com.example.data.SettingsEntity

enum class DeviceRole {
    CONTROLLER,
    FOLLOWER,
    DISPLAY,
}

data class DeviceIdentity(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String,
    val hardware: String,
    val sdkInt: Int,
) {
    fun matches(
        manufacturer: String? = null,
        brand: String? = null,
        model: String? = null,
        device: String? = null,
        product: String? = null,
    ): Boolean =
        (manufacturer == null || this.manufacturer.equals(manufacturer, ignoreCase = true)) &&
            (brand == null || this.brand.equals(brand, ignoreCase = true)) &&
            (model == null || this.model.equals(model, ignoreCase = true)) &&
            (device == null || this.device.equals(device, ignoreCase = true)) &&
            (product == null || this.product.equals(product, ignoreCase = true))
}

data class DevicePreset(
    val label: String,
    val role: DeviceRole = DeviceRole.FOLLOWER,
    val source: String = "build-fields",
) {
    val forceControllerMode: Boolean
        get() = role == DeviceRole.CONTROLLER
}

object DevicePresets {
    private const val PRESET_FILE_NAME = "device-preset.properties"

    fun currentIdentity(): DeviceIdentity =
        DeviceIdentity(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            product = Build.PRODUCT.orEmpty(),
            board = Build.BOARD.orEmpty(),
            hardware = Build.HARDWARE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
        )

    private fun presetFromInstallFile(context: Context): DevicePreset? {
        val file = context.filesDir.resolve(PRESET_FILE_NAME)
        if (!file.exists()) return null

        val values = file.readLines()
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    null
                } else {
                    val key = trimmed.substringBefore("=").trim()
                    val value = trimmed.substringAfter("=").trim()
                    key to value
                }
            }
            .toMap()

        val label = values["label"] ?: "Install-script preset"
        val role = when (values["role"]?.lowercase()) {
            "controller" -> DeviceRole.CONTROLLER
            "display" -> DeviceRole.DISPLAY
            "follower" -> DeviceRole.FOLLOWER
            else -> return null
        }
        return DevicePreset(
            label = label,
            role = role,
            source = "install-script",
        )
    }

    fun presetFor(context: Context? = null, identity: DeviceIdentity = currentIdentity()): DevicePreset {
        context?.let { presetFromInstallFile(it) }?.let { return it }

        return when {
            identity.matches(
                manufacturer = "OnePlus",
                brand = "OnePlus",
                model = "CPH2399",
                device = "OP557AL1",
                product = "CPH2399EEA",
            ) -> DevicePreset(
                label = "OnePlus CPH2399 follower",
                role = DeviceRole.FOLLOWER,
            )
            identity.matches(
                model = "23021RAA2Y",
                device = "topaz",
                product = "topaz_eea",
            ) -> DevicePreset(
                label = "Tablet 23021RAA2Y controller",
                role = DeviceRole.CONTROLLER,
            )
            identity.matches(
                manufacturer = "Xiaomi",
                brand = "Xiaomi",
                model = "2410CRP4CG",
                device = "uke",
                product = "uke_eea",
            ) -> DevicePreset(
                label = "Xiaomi 2410CRP4CG display",
                role = DeviceRole.DISPLAY,
            )
            else -> DevicePreset(label = "Default follower/device")
        }
    }

    fun applyStartupPreset(context: Context, current: SettingsEntity): SettingsEntity {
        val preset = presetFor(context = context)
        return when (preset.role) {
            DeviceRole.CONTROLLER -> if (!current.isController) current.copy(isController = true) else current
            DeviceRole.DISPLAY,
            DeviceRole.FOLLOWER -> if (current.isController) current.copy(isController = false) else current
        }
    }
}
