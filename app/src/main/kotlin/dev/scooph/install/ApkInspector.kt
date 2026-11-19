package dev.scooph.install

import android.content.pm.PackageManager
import android.os.Build
import dev.scooph.scan.fingerprint
import java.io.File
import java.security.cert.CertificateFactory
import java.util.Locale
import java.util.jar.JarFile

/**
 * 从 APK 文件里读出签名证书集合并算指纹（spec §10.3）。
 *
 * 生产路径走 `PackageManager.getPackageArchiveInfo`：v2/v3 签名没有
 * META-INF 证书文件，只有它能读全。JVM 单测没有 PackageManager 时退回到
 * 解 zip 里的 META-INF 签名块（v1 签名）。
 *
 * 指纹算法与 [dev.scooph.scan.AndroidPackageScanner] 共用 [fingerprint]，
 * 两边算出来的字符串可以直接比较。
 */
object ApkInspector {

    private val signatureSuffixes = listOf(".RSA", ".DSA", ".EC")

    @Volatile
    private var packageManager: PackageManager? = null

    /** 进程启动时注入一次；没注入时走 zip 里的 v1 证书（单测）。 */
    fun init(packageManager: PackageManager) {
        this.packageManager = packageManager
    }

    /** 读不出证书时返回空串；调用方必须把空串当作「无法校验」。 */
    fun fingerprintOf(apk: File): String {
        val pm = packageManager ?: return fingerprintFromZip(apk)
        return fingerprintFromArchive(pm, apk)
    }

    private fun fingerprintFromArchive(packageManager: PackageManager, apk: File): String {
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES.toLong()
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES.toLong()
            }
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.PackageInfoFlags.of(flags),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageArchiveInfo(apk.absolutePath, flags.toInt())
            } ?: return ""
        val certs =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.map { it.toByteArray() }.orEmpty()
            }
        return fingerprint(certs)
    }

    private fun fingerprintFromZip(apk: File): String {
        val certs = mutableListOf<ByteArray>()
        val factory = CertificateFactory.getInstance("X.509")
        JarFile(apk, false).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val name = entry.name.uppercase(Locale.ROOT)
                if (!name.startsWith("META-INF/")) continue
                if (signatureSuffixes.none { name.endsWith(it) }) continue
                jar.getInputStream(entry).use { input ->
                    factory.generateCertificates(input).forEach { certs += it.encoded }
                }
            }
        }
        return fingerprint(certs)
    }
}
