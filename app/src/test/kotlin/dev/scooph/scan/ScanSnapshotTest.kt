package dev.scooph.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 已装应用快照的签名指纹（spec §10.3）。
 *
 * 算法：每个证书 DER 字节做 SHA-256 → 小写十六进制 → 按字典序排序 → 无分隔拼接
 * → 整体再做一次 SHA-256 → 小写十六进制。空证书集合返回空串。
 *
 * 期望值是独立用 `sha256sum` 算出来的硬编码常量，不复用被测实现。
 */
class ScanSnapshotTest {

    private val certA = "cert-alpha".toByteArray(Charsets.UTF_8)
    private val certB = "cert-beta".toByteArray(Charsets.UTF_8)

    @Test
    fun `single certificate fingerprint is stable`() {
        val first = fingerprint(listOf(certA))
        // 同一输入重复调用必须完全一致（纯函数、无随机盐）。
        val second = fingerprint(listOf(certA.copyOf()))
        assertEquals(first, second)

        // sha256("sha256(\"cert-alpha\") 的十六进制串")
        assertEquals(
            "b09d5ff51624cd026ecc0a2be6ae97ce60379619a2cb36680249058a53eedbe2",
            first,
        )
        assertEquals(64, first.length)
        assertTrue("期望小写十六进制", first.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `multi certificate fingerprint ignores order`() {
        val ab = fingerprint(listOf(certA, certB))
        val ba = fingerprint(listOf(certB, certA))

        assertEquals(ab, ba)
        // sha256(排序后拼接的两个内层十六进制串)
        assertEquals(
            "5d24e967854dd2cc3fbf2c1d911f60f3ba5a4bff039c7b39ffcf177956af1417",
            ab,
        )
    }

    @Test
    fun `empty certificate list yields empty string`() {
        assertEquals("", fingerprint(emptyList()))
    }
}
