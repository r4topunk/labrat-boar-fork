package team.sopa.boar

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import team.sopa.boar.app.BoarServices

/** QA only (local): puts the devtest install back in the state ChatFlowDeviceTest needs after a setup run. */
@RunWith(AndroidJUnit4::class)
class QaStateDeviceTest {
    @Test fun removeTheSetupTestsPin() {
        val services = BoarServices.get(InstrumentationRegistry.getInstrumentation().targetContext)
        if (services.pinLock.isSet) services.pinLock.disable("2580") // the PIN SetupFlowDeviceTest sets
        assertFalse(services.pinLock.isSet)
    }
}
