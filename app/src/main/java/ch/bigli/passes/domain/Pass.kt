package ch.bigli.passes.domain

import kotlinx.serialization.Serializable
import java.time.Instant

enum class PassType { BOARDING, EVENT, LOYALTY, COUPON, GENERIC }
enum class SourceFormat { PKPASS, GOOGLE_JSON, PDF, MANUAL }
enum class BarcodeFormat { QR, PDF417, AZTEC, CODE128 }
enum class FieldPosition { HEADER, PRIMARY, SECONDARY, AUXILIARY }

@Serializable
data class PassField(val label: String, val value: String, val position: FieldPosition)

@Serializable
data class Barcode(val format: BarcodeFormat, val message: String, val altText: String?)

@Serializable
data class UpdateInfo(val webServiceUrl: String, val authToken: String, val serialNumber: String, val passTypeId: String)

data class Pass(
    val id: String,
    val type: PassType,
    val title: String,
    val subtitle: String?,
    val organization: String?,
    val bgColor: Long?,
    val fgColor: Long?,
    val fields: List<PassField>,
    val barcode: Barcode?,
    val relevantDate: Instant?,
    val expirationDate: Instant? = null,
    val rawFilePath: String,
    val sourceFormat: SourceFormat,
    val updateInfo: UpdateInfo?,
    val voided: Boolean = false,
    val lastModified: String? = null,
) {
    /** True if the issuer declared this pass voided, or its static [expirationDate] has passed. */
    fun isVoidedOrExpired(): Boolean = voided || expirationDate?.isBefore(Instant.now()) == true
}
