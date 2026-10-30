package ernest.airc

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*

/** Every poll uses the caller's bound writer; only a matching app-size task may replace its snapshot. */
suspend fun awaitAppSizes(output: String, request: suspend (String, JsonObject) -> String, onPartial: (List<InstalledApp>) -> Unit = {}): List<InstalledApp>? {
    val json=Json { ignoreUnknownKeys=true }
    val payload=Json.parseToJsonElement(output).jsonObject
    val initial=payload["sizeTask"]?.takeUnless { it is JsonNull } ?: return null
    return withTimeout(340_000) {
        var task=json.decodeFromJsonElement<TransferProgress>(initial)
        val id=task.id
        var lastProcessed=-1
        while(true) {
            check(task.id==id && task.direction=="app_sizes" && task.root=="apps" && task.name=="app-sizes" && task.unit=="items") { "应用大小任务对象不匹配" }
            val partial=task.result?.jsonObject
            if(partial!=null && task.phase!="completed") {
                check(partial["sizesRequested"]?.jsonPrimitive?.boolean==true) { "应用大小增量结果缺少统计标识" }
                val apps=decodeInstalledApps(partial.toString())
                val processed=partial["processed"]?.jsonPrimitive?.int ?: apps.size
                check(processed==apps.size && processed>=lastProcessed) { "应用大小增量结果顺序无效" }
                if(processed>lastProcessed) { onPartial(apps);lastProcessed=processed }
            }
            if(task.phase in listOf("completed","failed","cancelled"))break
            delay(300)
            task=json.decodeFromString(request("task.progress",buildJsonObject { put("id",id) }))
        }
        check(task.phase=="completed") { task.error ?: "读取应用大小失败" }
        val result=checkNotNull(task.result).jsonObject
        check(result["sizesRequested"]?.jsonPrimitive?.boolean==true) { "应用大小结果缺少统计标识" }
        decodeInstalledApps(result.toString())
    }
}

fun mergeAppSizes(current: List<InstalledApp>, measured: List<InstalledApp>, complete: Boolean = true): List<InstalledApp> {
    val byPackage=measured.associateBy { it.packageName }
    require(byPackage.size==measured.size) { "应用大小结果包含重复应用" }
    return current.map { app ->
        val received=byPackage[app.packageName]
        if(received==null && !complete) return@map app
        val size=received?.takeIf { it.cacheKey==app.cacheKey }
        app.copy(sizeBytes=size?.sizeBytes, sizeQueried=true)
    }
}
