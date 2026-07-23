package ch.bigli.passes.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.domain.TransitType
import ch.bigli.passes.importing.PassImporter
import ch.bigli.passes.importing.PdfImporter
import ch.bigli.passes.importing.PkPassImporter
import ch.bigli.passes.importing.PkPassLocalization
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
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
    fun observeAll(): Flow<List<Pass>> = dao.observeAll().map { list -> list.map { localize(it.toDomain()) } }

    suspend fun getById(id: String): Pass? = withContext(Dispatchers.IO) { dao.getById(id)?.toDomain()?.let { localize(it) } }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.getById(id)?.let { runCatching { File(it.rawFilePath).delete() } }
        dao.deleteById(id)
    }

    suspend fun setAutoUpdateEnabled(id: String, enabled: Boolean) =
        withContext(Dispatchers.IO) { dao.setAutoUpdateEnabled(id, enabled) }

    /**
     * Applies live pkpass localization: re-reads the pass's raw zip (still on disk, unmodified)
     * and translates field labels/values (including backFields), organization, subtitle, and
     * description against whatever language `Locale.getDefault()` currently returns — never
     * baked in at import time, so a device language change takes effect on the next read without
     * a re-import. Non-pkpass passes, and any pass whose raw file can't be read, pass through
     * unchanged.
     */
    private fun localize(pass: Pass): Pass {
        if (pass.sourceFormat != SourceFormat.PKPASS) return pass
        val bytes = runCatching { File(pass.rawFilePath).readBytes() }.getOrNull() ?: return pass
        val localization = runCatching { PkPassLocalization.forZip(bytes) }.getOrNull() ?: return pass

        fun translateField(f: PassField) = f.copy(
            label = localization.translate(f.label) ?: f.label,
            value = localization.translate(f.value) ?: f.value,
        )

        return pass.copy(
            fields = pass.fields.map(::translateField),
            backFields = pass.backFields.map(::translateField),
            organization = localization.translate(pass.organization),
            subtitle = localization.translate(pass.subtitle),
            description = localization.translate(pass.description),
        )
    }

    /**
     * Creates a pass from the manual-entry form (no source file). [fields], [relevantDate],
     * and [transitType] come from the kind-specific draft ([EventDraft]/[BoardingDraft]/
     * [LoyaltyDraft]/[GenericDraft]) the user filled in on [ch.bigli.passes.ui.CreatePassScreen].
     */
    suspend fun createManualPass(
        type: PassType,
        organization: String,
        fields: List<PassField>,
        relevantDate: Instant?,
        transitType: TransitType?,
        barcodeFormat: BarcodeFormat,
        barcodeValue: String,
    ): Pass = withContext(Dispatchers.IO) {
        val pass = Pass(
            id = UUID.randomUUID().toString(),
            type = type,
            subtitle = organization,
            organization = organization,
            bgColor = null,
            fgColor = null,
            fields = fields,
            barcode = Barcode(barcodeFormat, barcodeValue, null),
            relevantDate = relevantDate,
            rawFilePath = "",
            sourceFormat = SourceFormat.MANUAL,
            updateInfo = null,
            transitType = transitType,
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
                        pkPassImporter.import(bytes, pass.rawFilePath, "")
                    } catch (e: Exception) {
                        return@withContext RefreshResult.Error("malformed update: ${e.message}")
                    }
                    // Write to a temp file then rename, so a concurrent image read via
                    // ZipFile(rawFilePath) never sees a partially-written zip.
                    val target = File(pass.rawFilePath)
                    val tmp = File(target.parentFile, "${target.name}.tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(target)
                    val merged = fresh.copy(
                        id = pass.id,
                        // Deliberately NOT forced to false: trust whatever the freshly re-imported
                        // pass.json says, so an issuer that still declares the pass voided stays voided.
                        voided = fresh.voided,
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
