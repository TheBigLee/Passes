package ch.bigli.passes.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.importing.PassImporter
import ch.bigli.passes.importing.PdfImporter
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

sealed interface RefreshResult {
    data class Updated(val pass: Pass) : RefreshResult
    data object Unchanged : RefreshResult
    data object Voided : RefreshResult
    data object NotUpdatable : RefreshResult
    data class Error(val message: String) : RefreshResult
}

class PassRepository(
    private val context: Context,
    private val dao: PassDao,
    private val pkPassImporter: PkPassImporter = PkPassImporter(),
    private val pdfImporter: PdfImporter = PdfImporter(),
) {
    fun observeAll(): Flow<List<Pass>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: String): Pass? = withContext(Dispatchers.IO) { dao.getById(id)?.toDomain() }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.getById(id)?.let { runCatching { File(it.rawFilePath).delete() } }
        dao.deleteById(id)
    }

    /** Renames a pass. A blank/whitespace-only title is ignored so a pass can't be left untitled. */
    suspend fun updateTitle(id: String, title: String) = withContext(Dispatchers.IO) {
        val trimmed = title.trim()
        if (trimmed.isNotEmpty()) dao.updateTitle(id, trimmed)
    }

    /** Creates a pass from a manually-entered or scanned barcode (no source file). */
    suspend fun createManualPass(title: String, format: BarcodeFormat, value: String): Pass =
        withContext(Dispatchers.IO) {
            val pass = Pass(
                id = UUID.randomUUID().toString(),
                type = PassType.GENERIC,
                title = title.trim(),
                subtitle = null,
                organization = null,
                bgColor = null,
                fgColor = null,
                fields = emptyList(),
                barcode = Barcode(format, value, null),
                relevantDate = null,
                rawFilePath = "",
                sourceFormat = SourceFormat.MANUAL,
                updateInfo = null,
            )
            dao.insert(pass.toEntity())
            pass
        }

    /** Detects the format from [bytes], persists the raw file, imports, and stores the pass. */
    suspend fun import(bytes: ByteArray, displayName: String): Pass = withContext(Dispatchers.IO) {
        val (importer, ext) = when (detectPassFormat(bytes)) {
            SourceFormat.PKPASS -> pkPassImporter as PassImporter to "pkpass"
            SourceFormat.PDF -> pdfImporter as PassImporter to "pdf"
            else -> throw ImportError.UnsupportedFormat(displayName)
        }
        val dir = File(context.filesDir, "passes").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.$ext")
        target.writeBytes(bytes)
        val pass = try {
            importer.import(bytes, target.absolutePath, displayName)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        dao.insert(pass.toEntity())
        pass
    }

    /**
     * Reads the bytes behind [uri] (a content:// or file:// document) off the main thread and
     * imports them through [import]. Used by the file picker and by "Open with"/share intents.
     */
    suspend fun importFromUri(uri: Uri): Pass = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportError.CorruptFile("could not open $uri")
        import(bytes, displayName(uri))
    }

    /**
     * Downloads a .pkpass from [url] (http/https) off the main thread and imports it. Used by the
     * walletpasses:// "Add to Wallet" web flow. Throws [ImportError.CorruptFile] on a network/HTTP failure.
     */
    suspend fun importFromUrl(url: String): Pass = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        val bytes = try {
            val code = conn.responseCode
            if (code !in 200..299) throw ImportError.CorruptFile("download failed: HTTP $code")
            conn.inputStream.use { it.readBytes() }
        } catch (e: ImportError) {
            throw e
        } catch (e: Exception) {
            throw ImportError.CorruptFile("download failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { "pass.pkpass" }
        import(bytes, name)
    }

    /**
     * Polls the pass's `webServiceURL` (Apple PassKit web service protocol) for a fresher pkpass.
     * No-ops (returns [RefreshResult.NotUpdatable]) for passes without update info, non-pkpass
     * passes, and already-voided passes — none of these make a network request.
     */
    suspend fun refreshPass(id: String): RefreshResult = withContext(Dispatchers.IO) {
        val pass = dao.getById(id)?.toDomain() ?: return@withContext RefreshResult.NotUpdatable
        val update = pass.updateInfo
        if (pass.sourceFormat != SourceFormat.PKPASS || update == null || pass.voided) {
            return@withContext RefreshResult.NotUpdatable
        }
        val url = "${update.webServiceUrl.trimEnd('/')}/v1/passes/${update.passTypeId}/${update.serialNumber}"
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("Authorization", "ApplePass ${update.authToken}")
                pass.lastModified?.let { setRequestProperty("If-Modified-Since", it) }
            }
        } catch (e: Exception) {
            return@withContext RefreshResult.Error("connection failed: ${e.message}")
        }
        try {
            when (val code = conn.responseCode) {
                200 -> {
                    val bytes = conn.inputStream.use { it.readBytes() }
                    val fresh = try {
                        pkPassImporter.import(bytes, pass.rawFilePath, pass.title)
                    } catch (e: Exception) {
                        return@withContext RefreshResult.Error("malformed update: ${e.message}")
                    }
                    File(pass.rawFilePath).writeBytes(bytes)
                    val merged = fresh.copy(
                        id = pass.id,
                        title = pass.title,
                        voided = false,
                        lastModified = conn.getHeaderField("Last-Modified") ?: pass.lastModified,
                    )
                    dao.insert(merged.toEntity())
                    RefreshResult.Updated(merged)
                }
                304 -> RefreshResult.Unchanged
                410 -> {
                    dao.markVoided(pass.id)
                    RefreshResult.Voided
                }
                else -> RefreshResult.Error("unexpected status $code")
            }
        } catch (e: Exception) {
            RefreshResult.Error("refresh failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: "pass.pkpass"
    }
}
