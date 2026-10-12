package ernest.airc

import kotlinx.serialization.Serializable
@Serializable
data class AppInstallProposal(val id: String, val kind: String, val apk: TargetApkInfo, val currentApp: TargetAppInfo? = null, val userId: Int, val update: Boolean, val approvalRequired: Boolean, val codeMayAffectAllUsers: Boolean, val apks: List<TargetApkInfo> = emptyList())
data class AppInstallationState(val selectedApks: List<TargetApkInfo> = emptyList(), val busy: Boolean = false, val proposal: AppInstallProposal? = null, val phase: String = "idle", val bytes: Long = 0, val total: Long? = null, val message: String? = null, val error: String? = null)


/** Checks declared identities only; Android PackageManager verifies signatures and split dependencies. */
fun validateApkSet(apks: List<TargetApkInfo>) {
    require(apks.size in 1..32) { "请选择 1–32 个 APK" }
    val first = apks.first()
    require(apks.all { it.root == first.root }) { "多包必须位于同一文件范围" }
    require(apks.map { it.path }.distinct().size == apks.size) { "APK 路径重复" }
    require(apks.all { it.packageName == first.packageName && it.versionCode == first.versionCode }) { "APK 包名或版本不一致" }
    require(apks.count { it.split.isNullOrEmpty() } == 1) { "必须包含一个且仅一个 base APK" }
    require(apks.map { it.split.orEmpty() }.distinct().size == apks.size) { "split 名称重复" }
    require(apks.all { it.sizeBytes in 1..4L*1024*1024*1024 && it.sha256.matches(Regex("[a-f0-9]{64}")) } && apks.sumOf { it.sizeBytes } <= 4L*1024*1024*1024) { "APK 大小或摘要无效（整组上限 4 GiB）" }
    val known = apks.map { apk -> apk.signatures.flatMap { it.certificateSha256 }.toSet() }.filter { it.isNotEmpty() }
    require(known.distinct().size <= 1) { "APK 声明签名证书不一致" }
}
