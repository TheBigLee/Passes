package ch.bigli.passes.importing

import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.domain.UpdateInfo
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

class PkPassImporter : PassImporter {
    private val json = Json { ignoreUnknownKeys = true }

    override fun import(bytes: ByteArray, rawFilePath: String, @Suppress("UNUSED_PARAMETER") displayName: String): Pass {
        val passJsonBytes = extractPassJson(bytes)
        val pj = try {
            json.decodeFromString(PkPassJson.serializer(), passJsonBytes.decodeToString())
        } catch (e: Exception) {
            throw ImportError.CorruptFile("pass.json unparseable: ${e.message}")
        }

        val (type, structure) = resolveStructure(pj)
        val fields = buildList {
            structure?.headerFields?.forEach { add(it.toField(FieldPosition.HEADER)) }
            structure?.primaryFields?.forEach { add(it.toField(FieldPosition.PRIMARY)) }
            structure?.secondaryFields?.forEach { add(it.toField(FieldPosition.SECONDARY)) }
            structure?.auxiliaryFields?.forEach { add(it.toField(FieldPosition.AUXILIARY)) }
        }
        val primary = structure?.primaryFields.orEmpty()
        val title = when {
            primary.size >= 2 -> "${primary[0].label ?: primary[0].value} → ${primary[1].label ?: primary[1].value}"
            primary.isNotEmpty() -> primary[0].value
            else -> pj.description ?: pj.organizationName ?: "Pass"
        }

        val update = if (!pj.webServiceURL.isNullOrBlank() && !pj.authenticationToken.isNullOrBlank()
            && !pj.serialNumber.isNullOrBlank() && !pj.passTypeIdentifier.isNullOrBlank()
        ) {
            UpdateInfo(pj.webServiceURL, pj.authenticationToken, pj.serialNumber, pj.passTypeIdentifier)
        } else null

        return Pass(
            id = UUID.randomUUID().toString(),
            type = type,
            title = title,
            subtitle = pj.organizationName,
            organization = pj.organizationName,
            bgColor = parseColor(pj.backgroundColor),
            fgColor = parseColor(pj.foregroundColor),
            fields = fields,
            barcode = resolveBarcode(pj),
            relevantDate = pj.relevantDate?.let { runCatching { Instant.parse(it) }.getOrNull() },
            rawFilePath = rawFilePath,
            sourceFormat = SourceFormat.PKPASS,
            updateInfo = update,
            expirationDate = pj.expirationDate?.let { runCatching { Instant.parse(it) }.getOrNull() },
            voided = pj.voided ?: false,
        )
    }

    private fun extractPassJson(bytes: ByteArray): ByteArray {
        val out = try {
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var found: ByteArray? = null
                while (entry != null) {
                    if (entry.name == "pass.json") { found = zip.readBytes() }
                    entry = zip.nextEntry
                }
                found
            }
        } catch (e: Exception) {
            throw ImportError.CorruptFile("not a valid zip: ${e.message}")
        }
        return out ?: throw ImportError.CorruptFile("pass.json missing")
    }

    private fun resolveStructure(pj: PkPassJson): Pair<PassType, PkStructure?> = when {
        pj.boardingPass != null -> PassType.BOARDING to pj.boardingPass
        pj.eventTicket != null -> PassType.EVENT to pj.eventTicket
        pj.storeCard != null -> PassType.LOYALTY to pj.storeCard
        pj.coupon != null -> PassType.COUPON to pj.coupon
        else -> PassType.GENERIC to pj.generic
    }

    private fun resolveBarcode(pj: PkPassJson): Barcode? {
        val b = pj.barcode ?: pj.barcodes?.firstOrNull() ?: return null
        val fmt = when (b.format) {
            "PKBarcodeFormatQR" -> BarcodeFormat.QR
            "PKBarcodeFormatPDF417" -> BarcodeFormat.PDF417
            "PKBarcodeFormatAztec" -> BarcodeFormat.AZTEC
            "PKBarcodeFormatCode128" -> BarcodeFormat.CODE128
            else -> BarcodeFormat.QR
        }
        return Barcode(fmt, b.message, b.altText)
    }

    /** Apple uses "rgb(r, g, b)". Returns 0xAARRGGBB or null. */
    private fun parseColor(s: String?): Long? {
        if (s == null) return null
        val nums = Regex("""\d+""").findAll(s).map { it.value.toInt() }.toList()
        if (nums.size < 3) return null
        val (r, g, b) = nums
        return (0xFFL shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    private fun PkField.toField(pos: FieldPosition) = PassField(label ?: key, value, pos)
}
