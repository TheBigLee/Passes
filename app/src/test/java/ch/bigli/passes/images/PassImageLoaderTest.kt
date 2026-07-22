package ch.bigli.passes.images

import android.graphics.Bitmap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private fun pngBytes(width: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    @Test fun `prefers a localized image override when the current locale matches`() = runTest {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("de"))
            val out = java.io.ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("logo.png")); zip.write(pngBytes(1)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("de.lproj/logo.png")); zip.write(pngBytes(2)); zip.closeEntry()
            }
            val f = File.createTempFile("localized", ".pkpass")
            f.writeBytes(out.toByteArray())
            f.deleteOnExit()

            val bmp = loader.load(f.absolutePath, PassImage.LOGO)
            assertEquals(2, bmp!!.width)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test fun `falls back to the top-level image when the locale has no override`() = runTest {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("fr"))
            val out = java.io.ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("logo.png")); zip.write(pngBytes(1)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("de.lproj/logo.png")); zip.write(pngBytes(2)); zip.closeEntry()
            }
            val f = File.createTempFile("localized", ".pkpass")
            f.writeBytes(out.toByteArray())
            f.deleteOnExit()

            val bmp = loader.load(f.absolutePath, PassImage.LOGO)
            assertEquals(1, bmp!!.width)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
