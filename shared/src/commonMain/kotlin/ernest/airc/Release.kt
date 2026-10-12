package ernest.airc

import kotlinx.serialization.Serializable

@Serializable
data class RuntimeAsset(val abi: String, val file: String, val sha256: String, val size: Long, val url: String? = null)

@Serializable
data class RuntimeRelease(
    val version: String,
    val protocol: Int,
    val assets: List<RuntimeAsset>,
    val minAppVersionCode: Int = 1,
    val maxAppVersionCode: Int? = null,
) {
    fun validate(appVersionCode: Int, online: Boolean = false) {
        require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][a-zA-Z0-9.-]+)?"))) { "无效的程序版本" }
        require(protocol == PROTOCOL_VERSION) { "二进制协议不兼容" }
        require(minAppVersionCode > 0 && (maxAppVersionCode == null || maxAppVersionCode >= minAppVersionCode)) { "无效的应用兼容范围" }
        require(appVersionCode >= minAppVersionCode && (maxAppVersionCode == null || appVersionCode <= maxAppVersionCode)) { "当前应用版本与发布不兼容" }
        require(assets.isNotEmpty() && assets.map { it.abi }.distinct().size == assets.size) { "发布 ABI 为空或重复" }
        assets.forEach {
            require(it.abi in setOf("arm64-v8a", "armeabi-v7a", "x86_64")) { "不支持的发布 ABI" }
            require(it.file.matches(Regex("[a-zA-Z0-9_-][a-zA-Z0-9_.-]*"))) { "无效的资产文件名" }
            require(it.sha256.matches(Regex("[a-f0-9]{64}")) && it.size in 1..128L * 1024 * 1024) { "无效的资产摘要或大小" }
            if (online) require(it.url?.startsWith("https://") == true) { "在线资产需要 HTTPS 地址" }
        }
    }
    fun selectAsset(abis: List<String>): RuntimeAsset =
        abis.firstNotNullOfOrNull { abi -> assets.find { it.abi == abi.trim() } }
            ?: error("发布不支持目标 ABI：${abis.joinToString()}")
}
