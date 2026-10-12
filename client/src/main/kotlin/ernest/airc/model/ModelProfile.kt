package ernest.airc

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun canonicalModelUrl(value: String): String {
    val url = requireNotNull(value.trim().toHttpUrlOrNull()) { "请输入有效的 HTTP(S) API 地址" }
    require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
        "API 地址不能包含凭据、查询参数或片段"
    }
    return url.toString().trimEnd('/')
}

fun ModelProfile.validated(requireModel: Boolean = false): ModelProfile {
    require(apiKey.isNotBlank() && apiKey.length <= 8192 && apiKey.none { it.isISOControl() }) { "请输入有效的 API Key" }
    require(model.length <= 256 && model.none { it.isISOControl() }) { "模型名称无效" }
    if (requireModel) require(model.isNotBlank()) { "请选择或输入模型名称" }
    return copy(baseUrl = canonicalModelUrl(baseUrl), apiKey = apiKey.trim(), model = model.trim())
}
