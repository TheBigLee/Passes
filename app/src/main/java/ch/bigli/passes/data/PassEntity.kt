package ch.bigli.passes.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.domain.UpdateInfo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

@Entity(tableName = "passes")
data class PassEntity(
    @PrimaryKey val id: String,
    val type: String,
    val subtitle: String?,
    val organization: String?,
    val bgColor: Long?,
    val fgColor: Long?,
    val fieldsJson: String,
    val barcodeJson: String?,
    val relevantDateEpoch: Long?,
    val rawFilePath: String,
    val sourceFormat: String,
    val updateInfoJson: String?,
    val voided: Boolean = false,
    val lastModified: String? = null,
    val expirationDateEpoch: Long? = null,
    val description: String? = null,
    val backFieldsJson: String = "[]",
    val autoUpdateEnabled: Boolean = true,
)

fun Pass.toEntity() = PassEntity(
    id = id,
    type = type.name,
    subtitle = subtitle,
    organization = organization,
    bgColor = bgColor,
    fgColor = fgColor,
    fieldsJson = json.encodeToString(ListSerializer(PassField.serializer()), fields),
    barcodeJson = barcode?.let { json.encodeToString(Barcode.serializer(), it) },
    relevantDateEpoch = relevantDate?.toEpochMilli(),
    rawFilePath = rawFilePath,
    sourceFormat = sourceFormat.name,
    updateInfoJson = updateInfo?.let { json.encodeToString(UpdateInfo.serializer(), it) },
    voided = voided,
    lastModified = lastModified,
    expirationDateEpoch = expirationDate?.toEpochMilli(),
    description = description,
    backFieldsJson = json.encodeToString(ListSerializer(PassField.serializer()), backFields),
    autoUpdateEnabled = autoUpdateEnabled,
)

fun PassEntity.toDomain() = Pass(
    id = id,
    type = PassType.valueOf(type),
    subtitle = subtitle,
    organization = organization,
    bgColor = bgColor,
    fgColor = fgColor,
    fields = json.decodeFromString(ListSerializer(PassField.serializer()), fieldsJson),
    backFields = json.decodeFromString(ListSerializer(PassField.serializer()), backFieldsJson),
    barcode = barcodeJson?.let { json.decodeFromString(Barcode.serializer(), it) },
    relevantDate = relevantDateEpoch?.let { java.time.Instant.ofEpochMilli(it) },
    rawFilePath = rawFilePath,
    sourceFormat = SourceFormat.valueOf(sourceFormat),
    updateInfo = updateInfoJson?.let { json.decodeFromString(UpdateInfo.serializer(), it) },
    voided = voided,
    lastModified = lastModified,
    expirationDate = expirationDateEpoch?.let { java.time.Instant.ofEpochMilli(it) },
    description = description,
    autoUpdateEnabled = autoUpdateEnabled,
)
