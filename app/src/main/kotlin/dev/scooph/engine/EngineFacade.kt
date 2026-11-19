package dev.scooph.engine

import dev.scooph.engine.api.Api
import dev.scooph.engine.api.Engine as NativeEngine
import java.io.File

/**
 * Kotlin 侧唯一接触 Go AAR 的入口：JSON 进、JSON 出。
 *
 * AAR 里的 [Api]/[NativeEngine] 由 gomobile bind 生成。它们的 native 库
 * `lib/<abi>/libgojni.so` 只有 Android 运行时能加载；JVM 单测（`:app:testDebugUnitTest`）
 * 里调用会选择占位结果，真正的 native 调用验证放在能在 Android 运行时跑的任务里。
 */
class EngineFacade(rootDir: File) : EngineGateway {
    private val root: String = rootDir.apply { mkdirs() }.absolutePath

    private val native: NativeEngine? = openNative()

    /** 引擎版本；native 不可用时（JVM 单测）返回占位值。 */
    override fun version(): String =
        try {
            native?.version()?.takeIf { it.isNotBlank() } ?: JVM_PLACEHOLDER_VERSION
        } catch (_: LinkageError) {
            JVM_PLACEHOLDER_VERSION
        }

    /** 同步一个 bucket。 */
    override fun sync(url: String): SyncResultDto {
        val raw =
            nativeCall { it.sync(url) } ?: """
            {"commit":"","files":[],"empty":true}
            """.trimIndent()
        return EngineJson.parseSync(raw)
    }

    /** 读取 bucket 条目与丢弃原因。 */
    override fun load(url: String): LoadResultDto {
        val raw =
            nativeCall { it.load(url) } ?: """
            {"entries":[],"drops":[]}
            """.trimIndent()
        return EngineJson.parseLoad(raw)
    }

    /** 计算更新计划。 */
    override fun plan(request: PlanRequestDto): PlanResultDto {
        val reqJson = EngineJson.encodePlanRequest(request)
        val raw = nativeCall { it.plan(reqJson) } ?: """{"items":[]}"""
        return EngineJson.parsePlan(raw)
    }

    private fun openNative(): NativeEngine? =
        try {
            Api.newEngine(root)
        } catch (_: LinkageError) {
            // gomobile 的 go.Seq 在类初始化时 System.loadLibrary("gojni")；
            // JVM 上没有 Android ABI 的 .so，会抛 UnsatisfiedLinkError
            // 或包一层的 ExceptionInInitializerError / NoClassDefFoundError。
            null
        }

    private inline fun nativeCall(block: (NativeEngine) -> String): String? {
        return try {
            native?.let(block)
        } catch (_: LinkageError) {
            null
        }
    }

    companion object {
        /** JVM 单测专用占位版本：真实版本由 Go 的 Version() 提供。 */
        internal const val JVM_PLACEHOLDER_VERSION = "0.1.0-jvm"
    }
}
