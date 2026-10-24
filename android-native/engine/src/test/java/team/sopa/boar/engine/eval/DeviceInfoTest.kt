package team.sopa.boar.engine.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceInfoTest {
    @Test fun `hardware descriptions`() {
        assertEquals(listOf("fp", "asimd", "asimddp", "i8mm"), DeviceInfo.parseCpuFeatures("fp asimd  asimddp i8mm\n"))
        assertEquals("Mediatek MT6897", DeviceInfo.describeChipset(DeviceInfo.Device(soc = "MT6897", socManufacturer = "Mediatek")))
        assertEquals("qcom", DeviceInfo.describeChipset(DeviceInfo.Device(hardware = "qcom")))
        assertEquals("1 × 3.35 GHz + 3 × 3.2 GHz + 4 × 2.2 GHz", DeviceInfo.describeCores(listOf(2200000, 2200000, 2200000, 2200000, 3200000, 3200000, 3200000, 3350000)))
        assertNull(DeviceInfo.describeCores(listOf(0, 0)))
    }
}
