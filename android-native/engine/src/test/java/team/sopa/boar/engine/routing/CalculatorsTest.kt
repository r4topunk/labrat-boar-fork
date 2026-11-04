package team.sopa.boar.engine.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calculators cases the recorded goldens do not cover: which way a currency amount is converted, and numbers written with
 * comma thousands and a decimal ("50,000.0 mAh"), whose whole value the lexer has to capture.
 */
class CalculatorsTest {
    private fun text(q: String): String = Calculators.calculate(q)!!.text

    @Test fun `an amount in the base currency is multiplied and comes out in the quoted one`() {
        assertEquals("50 dollars × 0.92 = 46 EUR.", text("1 USD = 0.92 EUR, how many eur is 50 dollars?"))
        assertEquals("50 euros × 1.09 = 54.5 USD.", text("1 EUR = 1.09 USD, how many dollars is 50 euros?"))
        assertEquals("100 dollars × 0.92 = 92 eur.", text("1 dollar is worth 0.92 eur: 100 dollars"))
    }

    @Test fun `an amount in the quoted currency is divided and comes out in the base`() {
        assertEquals("50 euros ÷ 0.92 = 54.35 USD.", text("1 USD = 0.92 EUR, how many dollars is 50 euros?"))
        assertEquals("50 dollars ÷ 1.09 = 45.87 EUR.", text("1 EUR = 1.09 USD, how many euros is 50 dollars?"))
    }

    @Test fun `a stray word after the quoted currency is not part of the label`() {
        assertEquals("50 dollars × 0.92 = 46 EUR.", text("1 USD = 0.92 EUR how many eur is 50 dollars"))
    }

    @Test fun `the tip is added in the currency the amount comes out in`() {
        assertEquals(
            "100 dollars × 0.92 = 92 EUR. With a 10% tip: 101.2 EUR (tip 9.2).",
            text("1 USD = 0.92 EUR, 100 dollars with a 10% tip, in eur"),
        )
    }

    @Test fun `comma thousands with a decimal parse whole`() {
        for ((raw, want) in listOf("50,000.0" to 50000.0, "50,000" to 50000.0, "50000" to 50000.0, "1,234.5" to 1234.5, "1,234,567.25" to 1234567.25)) {
            assertEquals(raw, want, Calculators.parseNumber(raw), 0.0)
        }
    }

    @Test fun `a grouped decimal battery is the same Wh as the plain number`() {
        val want = "= 185 Wh. Above 160 Wh it is not allowed on passenger flights."
        for (mah in listOf("50,000.0", "50,000", "50000", "50000.0")) {
            val t = text("Can I take a $mah mAh 3.7 V battery on a flight?")
            assertTrue("$mah: $t", t.endsWith(want))
        }
        assertTrue(text("1,234.5 mAh 3.7 V").endsWith("= 4.57 Wh."))
        assertTrue(text("10,000.5 mAh 3.7 V").endsWith("= 37 Wh."))
    }

    @Test fun `the flight verdict does not change with a decimal after the thousands`() {
        assertTrue(text("20,000.0 mAh 3.7 V on a plane?").contains("= 74 Wh. Yes, you can take it on a plane"))
        assertTrue(text("30,000.0 mAh 3.7 V on a plane?").contains("= 111 Wh. Between 100 and 160 Wh"))
        assertTrue(text("50,000.0 mAh 3.7 V on a plane?").contains("= 185 Wh. Above 160 Wh"))
        assertNotNull(Calculators.calculate("50,000.0 mAh 3.7 V"))
    }

    @Test fun `a number longer than three digits after the comma is left as it was`() {
        assertEquals(1.2345, Calculators.parseNumber("1,2345"), 0.0)
        assertTrue(text("1,2345 mAh 1 V").startsWith("1 mAh × 1 V"))
    }
}
