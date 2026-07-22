package ch.bigli.passes.importing

import kotlinx.serialization.Serializable

@Serializable
data class PkPassJson(
    val organizationName: String? = null,
    val description: String? = null,
    val serialNumber: String? = null,
    val passTypeIdentifier: String? = null,
    val backgroundColor: String? = null,
    val foregroundColor: String? = null,
    val relevantDate: String? = null,
    val expirationDate: String? = null,
    val voided: Boolean? = null,
    val barcode: PkBarcode? = null,
    val barcodes: List<PkBarcode>? = null,
    val webServiceURL: String? = null,
    val authenticationToken: String? = null,
    val boardingPass: PkStructure? = null,
    val eventTicket: PkStructure? = null,
    val storeCard: PkStructure? = null,
    val coupon: PkStructure? = null,
    val generic: PkStructure? = null,
)

@Serializable
data class PkBarcode(
    val format: String,
    val message: String,
    val altText: String? = null,
)

@Serializable
data class PkStructure(
    val headerFields: List<PkField>? = null,
    val primaryFields: List<PkField>? = null,
    val secondaryFields: List<PkField>? = null,
    val auxiliaryFields: List<PkField>? = null,
    val backFields: List<PkField>? = null,
)

@Serializable
data class PkField(
    val key: String,
    val label: String? = null,
    val value: String,
)
