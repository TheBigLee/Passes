package ch.bigli.passes.importing

import ch.bigli.passes.domain.Pass

/** Converts raw file bytes of one specific format into a domain [Pass]. */
interface PassImporter {
    /**
     * @param rawFilePath where the raw bytes are persisted; stored on the Pass.
     * @param displayName the original file name; used by importers that lack an internal
     *   title (e.g. PDF). Importers with their own title (pkpass) may ignore it.
     */
    fun import(bytes: ByteArray, rawFilePath: String, displayName: String): Pass
}
