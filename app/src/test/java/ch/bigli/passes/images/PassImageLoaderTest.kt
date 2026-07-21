package ch.bigli.passes.images

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PassImageLoaderTest {
    private val loader = PassImageLoader()

    private fun fixtureFile(name: String): String {
        val bytes = checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()
        val f = File.createTempFile(name, ".pkpass")
        f.writeBytes(bytes)
        f.deleteOnExit()
        return f.absolutePath
    }

    @Test fun `loads best-resolution logo and strip from a pkpass with images`() = runTest {
        val path = fixtureFile("withimages.pkpass")
        val logo = loader.load(path, PassImage.LOGO)
        val strip = loader.load(path, PassImage.STRIP)
        assertNotNull(logo)
        assertNotNull(strip)
        assertEquals(3, logo!!.width)  // logo@3x.png preferred over logo@2x.png
        assertEquals(4, strip!!.width) // strip@2x.png (only strip present)
    }

    @Test fun `returns null for a pkpass without images`() = runTest {
        val path = fixtureFile("sample.pkpass")
        assertNull(loader.load(path, PassImage.LOGO))
    }

    @Test fun `returns null for an unreadable path`() = runTest {
        assertNull(loader.load("/definitely/not/here.pkpass", PassImage.LOGO))
    }

    @Test fun `caches the decoded bitmap`() = runTest {
        val path = fixtureFile("withimages.pkpass")
        val first = loader.load(path, PassImage.LOGO)
        val second = loader.load(path, PassImage.LOGO)
        assertNotNull(first)
        assertSame(first, second)
    }
}
