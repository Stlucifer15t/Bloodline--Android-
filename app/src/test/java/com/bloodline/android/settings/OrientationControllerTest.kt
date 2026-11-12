package com.bloodline.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The orientation rules.
 *
 * The behaviour that matters is not "which constant gets set" — it is that the
 * game's landscape requirement can never be overridden by a launcher
 * preference, and that leaving the game puts the launcher back the way the user
 * had it.
 */
class OrientationControllerTest {

    private val applied = mutableListOf<OrientationRequest>()
    private val controller = OrientationController { applied += it }

    @Test
    fun `the launcher follows the device rather than pinning an orientation`() {
        controller.applyLauncher()

        assertEquals(OrientationRequest.FOLLOW_DEVICE, controller.currentRequest)
        assertEquals(OrientationPhase.LAUNCHER, controller.currentPhase)
    }

    @Test
    fun `gameplay forces landscape whatever the launcher was doing`() {
        controller.applyLauncher()
        controller.enterGameplay()

        assertEquals(OrientationRequest.SENSOR_LANDSCAPE, controller.currentRequest)
        assertEquals(OrientationPhase.GAMEPLAY, controller.currentPhase)
    }

    @Test
    fun `leaving gameplay hands the orientation back to the launcher`() {
        controller.applyLauncher()
        controller.enterGameplay()
        controller.exitGameplay()

        assertEquals(OrientationRequest.FOLLOW_DEVICE, controller.currentRequest)
        assertEquals(OrientationPhase.LAUNCHER, controller.currentPhase)
    }

    @Test
    fun `a launcher request other than the default is restored as well`() {
        controller.applyLauncher(OrientationRequest.SENSOR_LANDSCAPE)
        controller.enterGameplay()
        controller.exitGameplay()

        assertEquals(OrientationRequest.SENSOR_LANDSCAPE, controller.currentRequest)
    }

    @Test
    fun `rotating into the game never leaves the launcher sideways`() {
        controller.applyLauncher()
        controller.enterGameplay()
        controller.exitGameplay()
        controller.enterGameplay()
        controller.exitGameplay()

        assertEquals(
            listOf(
                OrientationRequest.FOLLOW_DEVICE,
                OrientationRequest.SENSOR_LANDSCAPE,
                OrientationRequest.FOLLOW_DEVICE,
                OrientationRequest.SENSOR_LANDSCAPE,
                OrientationRequest.FOLLOW_DEVICE,
            ),
            applied,
        )
    }

    @Test
    fun `asking for the orientation already in use does not ask Android again`() {
        controller.applyLauncher()
        controller.applyLauncher()
        controller.enterGameplay()
        controller.enterGameplay()

        // Two real changes: launcher, then gameplay. The repeats are dropped so
        // the Activity is not pushed through a needless configuration change.
        assertEquals(2, applied.size)
    }

    @Test
    fun `nothing is requested before the first phase is applied`() {
        assertNull(controller.currentRequest)
        assertEquals(0, applied.size)
    }

    @Test
    fun `the launcher phase is the default, so a fresh controller cannot pin the screen`() {
        assertEquals(OrientationPhase.LAUNCHER, controller.currentPhase)
    }
}
