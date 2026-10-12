package ernest.airc
import kotlin.test.*
class ApkSetTest {
    private val base = TargetApkInfo("inbox","base.apk","test.app",1,sizeBytes=10,sha256="a".repeat(64),signatures=emptyList(),signatureVerified=false)
    private val split = base.copy(path="split.apk",split="config.en",sha256="b".repeat(64))
    @Test fun acceptsBaseAndSplitsInAnyOrder() { validateApkSet(listOf(base));validateApkSet(listOf(split,base)) }
    @Test fun rejectsMissingBaseDuplicateSplitAndIdentityMismatch() {
        for(apks in listOf(emptyList(),listOf(split),listOf(base,base),listOf(base,split,split.copy(path="other.apk")),listOf(base,split.copy(packageName="other.app")),listOf(base,split.copy(versionCode=2)),listOf(base,split.copy(root="downloads")),listOf(base,split.copy(sizeBytes=4L*1024*1024*1024)))) assertFails { validateApkSet(apks) }
    }
    @Test fun rejectsDifferentDeclaredCertificates() {
        val sig = ApkSignatureInfo("v2",listOf("first"),true)
        assertFails { validateApkSet(listOf(base.copy(signatures=listOf(sig)),split.copy(signatures=listOf(sig.copy(certificateSha256=listOf("second")))))) }
    }
}
