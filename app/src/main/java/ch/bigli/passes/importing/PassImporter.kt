package ch.bigli.passes.importing

import ch.bigli.passes.domain.Pass

/** Converts raw file bytes of one specific format into a domain [Pass]. */
interface PassImporter {
    /** @param rawFilePath where the raw bytes will be persisted; stored on the Pass. */
    fun import(bytes: ByteArray, rawFilePath: String): Pass
}
