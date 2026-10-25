package team.sopa.boar.app

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import team.sopa.boar.engine.CpuTopology
import team.sopa.boar.engine.eval.DeviceInfo
import java.io.File

/** What Settings → Telemetry and the Evaluation screen show about this phone. It stays on the phone: LabRat has no network. */
object DeviceReader {
    fun device(context: Context): DeviceInfo.Device {
        val ram = runCatching { ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem }.getOrNull()
        val features = runCatching { File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }?.substringAfter(":") }.getOrNull()
        val freqs = CpuTopology.read().cores.sortedBy { it.id }.map { it.maxKhz }
        return DeviceInfo.Device(
            osVersion = Build.VERSION.RELEASE, apiLevel = Build.VERSION.SDK_INT, brand = Build.BRAND, model = Build.MODEL,
            soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it != Build.UNKNOWN } else null,
            socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.takeIf { it != Build.UNKNOWN } else null,
            hardware = Build.HARDWARE, ramBytes = ram,
            cpuCores = freqs.size.takeIf { it > 0 }, cpuFeatures = DeviceInfo.parseCpuFeatures(features),
            coreMaxFreqKHz = freqs.takeIf { f -> f.any { it > 0 } },
        )
    }

    fun appVersion(context: Context): String = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
}
