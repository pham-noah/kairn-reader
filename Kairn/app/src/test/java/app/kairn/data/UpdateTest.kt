package app.kairn.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {

    @Test
    fun `a higher patch, minor or major is newer`() {
        assertTrue(Update.isNewer("0.8.1", "0.8.0"))
        assertTrue(Update.isNewer("0.9.0", "0.8.9"))
        assertTrue(Update.isNewer("1.0.0", "0.99.99"))
    }

    @Test
    fun `the same version or an older one is not`() {
        assertFalse(Update.isNewer("0.8.0", "0.8.0"))
        assertFalse(Update.isNewer("0.7.9", "0.8.0"))
    }

    @Test
    fun `a numeric compare, not a string compare`() {
        // "0.10.0" < "0.9.0" as strings, which would hide every release after 0.9.
        assertTrue(Update.isNewer("0.10.0", "0.9.0"))
    }

    @Test
    fun `anything unparseable is not an update`() {
        assertFalse(Update.isNewer("nightly", "0.8.0"))
        assertFalse(Update.isNewer("0.8.1", ""))
    }
}
