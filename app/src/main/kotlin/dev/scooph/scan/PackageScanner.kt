package dev.scooph.scan

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** 本机已安装应用快照。 */
data class InstalledApp(
    val packageName: String,
    val versionCode: Long,
    val signature: String?,
    val hasSplits: Boolean,
    val label: String,
)

/** 扫描本机已安装应用。 */
interface PackageScanner {
    suspend fun scan(): List<InstalledApp>
}

/**
 * 证书集合指纹（spec §10.3）：
 * 1) 每个证书 bytes 做 SHA-256 -> hex
 * 2) 按字典序排序并无分隔拼接
 * 3) 对拼接后的 UTF-8 bytes 再做 SHA-256 -> hex
 * 空集合返回空串。
 */
fun fingerprint(certs: List<ByteArray>): String {
    if (certs.isEmpty()) return ""
    val perCert = certs.map { sha256Hex(it) }.sorted()
    return sha256Hex(perCert.joinToString(separator = "").toByteArray(Charsets.UTF_8))
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val out = StringBuilder(digest.size * 2)
    for (b in digest) {
        out.append(((b.toInt() ushr 4) and 0x0f).toString(16))
        out.append((b.toInt() and 0x0f).toString(16))
    }
    return out.toString()
}

/** Android 平台实现：从 PackageManager 扫描已安装应用。 */
class AndroidPackageScanner(
    private val packageManager: PackageManager,
) : PackageScanner {

    override suspend fun scan(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val installed = installedPackages(packageManager)
        installed.map { info ->
            val certs = signingCertBytes(info)
            val fp = fingerprint(certs).ifBlank { null }
            val appInfo = info.applicationInfo
            InstalledApp(
                packageName = info.packageName,
                versionCode = PackageInfoCompat.getLongVersionCode(info),
                signature = fp,
                hasSplits = !appInfo?.splitSourceDirs.isNullOrEmpty(),
                label = appInfo?.let { packageManager.getApplicationLabel(it).toString() }.orEmpty(),
            )
        }
    }

    private fun installedPackages(pm: PackageManager): List<PackageInfo> {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES.toLong()
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES.toLong()
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flag))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flag.toInt())
        }
    }

    private fun signingCertBytes(info: PackageInfo): List<ByteArray> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
    }
}
