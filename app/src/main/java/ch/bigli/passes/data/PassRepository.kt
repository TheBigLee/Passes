package ch.bigli.passes.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PassRepository(
    private val context: Context,
    private val dao: PassDao,
    private val pkPassImporter: PkPassImporter,
) {
    fun observeAll(): Flow<List<Pass>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: String): Pass? = withContext(Dispatchers.IO) { dao.getById(id)?.toDomain() }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.getById(id)?.let { runCatching { File(it.rawFilePath).delete() } }
        dao.deleteById(id)
    }

    /** @param displayName the original file name, used only for format sniffing. */
    suspend fun import(bytes: ByteArray, displayName: String): Pass = withContext(Dispatchers.IO) {
        if (!isPkPass(bytes, displayName)) {
            throw ImportError.UnsupportedFormat(displayName)
        }
        val dir = File(context.filesDir, "passes").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.pkpass")
        target.writeBytes(bytes)
        val pass = try {
            pkPassImporter.import(bytes, target.absolutePath)
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

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: "pass.pkpass"
    }

    private fun isPkPass(bytes: ByteArray, name: String): Boolean {
        val zipMagic = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        return zipMagic && (name.endsWith(".pkpass", true) || name.endsWith(".zip", true) || true)
    }
}
