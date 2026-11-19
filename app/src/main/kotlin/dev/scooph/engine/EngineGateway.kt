package dev.scooph.engine

/**
 * Kotlin 侧对 Go 引擎的最小端口。
 *
 * 生产实现是 [EngineFacade]；单测直接喂固定 DTO，不必加载 gomobile 的 .so。
 * 所有方法保持同步语义：真正的 IO 在 Go 侧完成，调用方负责切线程。
 */
interface EngineGateway {
    fun version(): String

    fun sync(url: String): SyncResultDto

    fun load(url: String): LoadResultDto

    fun plan(request: PlanRequestDto): PlanResultDto

    /**
     * 下载 APK 到 [destPath] 并校验 sha256；校验失败会抛异常（不返回坏文件）。
     * [wantSHA256] 为空表示跳过校验。
     */
    fun fetch(url: String, destPath: String, wantSHA256: String): FetchResultDto
}
