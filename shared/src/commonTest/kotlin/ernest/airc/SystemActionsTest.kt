package ernest.airc
import kotlin.test.*
class SystemActionsTest {
    @Test fun malformedOrNonRootProposalCannotReachConfirmation(){
        val p=SystemActionProposal("12345678-1234-1234-1234-123456789abc","system_action","reboot",0,true,true,120)
        assertTrue(validSystemProposal(p))
        for(invalid in listOf(p.copy(runtimeUid=2000),p.copy(approvalRequired=false),p.copy(action="shell"),p.copy(expiresInSeconds=121),p.copy(connectionMayClose=false),p.copy(id="invalid")))assertFalse(validSystemProposal(invalid))
    }
}
