package ernest.airc

class RuntimeAlignmentRequired(val actual: RuntimeStatus, val expectedVersion: String) :
    IllegalStateException("被控端程序与当前控制端不一致，需要部署配套版本 $expectedVersion")

/** Version alone cannot distinguish two development builds of the same release. */
fun requireRuntimeAlignment(status: RuntimeStatus, release: RuntimeRelease) {
    if (status.version != release.version || status.protocol != release.protocol ||
        status.binarySha256 == null || release.assets.none { it.sha256 == status.binarySha256 }) {
        throw RuntimeAlignmentRequired(status, release.version)
    }
}
