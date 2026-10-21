package ernest.airc

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class RoutineConditionsTest {
    @Test fun checksStructuredEvidenceAndDecodedAttributes() {
        assertTrue(routineConditionMet(RoutineStep("wait_text", "A&B"), """{"xml":"<hierarchy><node text=\"A&amp;B\" /></hierarchy>"}"""))
        assertFalse(routineConditionMet(RoutineStep("wait_text", "node"), """{"xml":"<hierarchy><node text=\"hello\" /></hierarchy>"}"""))
        assertTrue(routineConditionMet(RoutineStep("wait_playing", ""), """{"sessions":[{"active":true,"state":3}]}"""))
        assertFalse(routineConditionMet(RoutineStep("wait_playing", ""), """{"sessions":[{"active":false,"state":3}]}"""))
        assertFalse(routineConditionMet(RoutineStep("wait_app", "test.app"), """{"packageName":null}"""))
        assertFails { routineConditionMet(RoutineStep("wait_text", "secret"), """{"xml":"<!DOCTYPE hierarchy [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><hierarchy><node text='&x;'/></hierarchy>"}""") }
    }
    @Test fun pollsUntilConditionAndTimeoutStopsFollowingActions() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var probes = 0; var actions = 0
        val controller = QuickRoutineController(scope) { tool, _ ->
            if (tool == "device.foreground") { probes++; if (probes < 2) "{}" else """{"packageName":"test.app"}""" }
            else { actions++; "{}" }
        }
        try {
            controller.start(QuickRoutine("r", "condition", listOf(RoutineStep("wait_app", "test.app"), RoutineStep("app", "test.next"))), true)
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertEquals(2, probes); assertEquals(1, actions)
            controller.start(QuickRoutine("r", "timeout", listOf(RoutineStep("wait_app", "never.app", 1), RoutineStep("app", "test.next"))), true)
            withTimeout(3000) { controller.state.first { !it.busy } }
            assertNotNull(controller.state.value.error); assertEquals(1, actions)
        } finally { scope.cancel() }
    }
}
