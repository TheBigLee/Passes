package ch.bigli.passes.ui

import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.TransitType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val MANUAL_PASS_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
internal val MANUAL_PASS_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Combines [date] and [time] into an [Instant] in the device's local zone - null unless both are set. */
private fun combineDateTime(date: LocalDate?, time: LocalTime?): Instant? {
    if (date == null || time == null) return null
    return date.atTime(time).atZone(ZoneId.systemDefault()).toInstant()
}

/** Manual-entry form state for a [ch.bigli.passes.domain.PassType.EVENT] pass. */
data class EventDraft(
    val eventName: String = "",
    val location: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
) {
    fun toPassFields(): List<PassField> = buildList {
        if (eventName.isNotBlank()) add(PassField("Event", eventName, FieldPosition.PRIMARY))
        if (location.isNotBlank()) add(PassField("Location", location, FieldPosition.SECONDARY))
        date?.let { add(PassField("Date", it.format(MANUAL_PASS_DATE_FORMAT), FieldPosition.AUXILIARY)) }
        time?.let { add(PassField("Time", it.format(MANUAL_PASS_TIME_FORMAT), FieldPosition.AUXILIARY)) }
    }

    fun toRelevantDate(): Instant? = combineDateTime(date, time)

    /** Without an event name there's no PRIMARY field, so the pass has no headline at all. */
    val isValid: Boolean get() = eventName.isNotBlank()
}

/** Manual-entry form state for a [ch.bigli.passes.domain.PassType.BOARDING] pass. */
data class BoardingDraft(
    val from: String = "",
    val to: String = "",
    val transitType: TransitType = TransitType.GENERIC,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
) {
    fun toPassFields(): List<PassField> = buildList {
        if (from.isNotBlank()) add(PassField("Departure", from, FieldPosition.PRIMARY))
        if (to.isNotBlank()) add(PassField("Arrival", to, FieldPosition.PRIMARY))
        date?.let { add(PassField("Date", it.format(MANUAL_PASS_DATE_FORMAT), FieldPosition.AUXILIARY)) }
        time?.let { add(PassField("Boards", it.format(MANUAL_PASS_TIME_FORMAT), FieldPosition.AUXILIARY)) }
    }

    fun toRelevantDate(): Instant? = combineDateTime(date, time)

    /** [BoardingFieldsLayout] only renders its two-up row for exactly 2 PRIMARY fields. */
    val isValid: Boolean get() = from.isNotBlank() && to.isNotBlank()
}

/** Manual-entry form state for a [ch.bigli.passes.domain.PassType.LOYALTY] pass. */
data class LoyaltyDraft(
    val memberName: String = "",
    val details: String = "",
) {
    fun toPassFields(): List<PassField> = buildList {
        if (memberName.isNotBlank()) add(PassField("Member", memberName, FieldPosition.AUXILIARY))
        if (details.isNotBlank()) add(PassField("Details", details, FieldPosition.AUXILIARY))
    }
}

/** Manual-entry form state for a [ch.bigli.passes.domain.PassType.GENERIC] pass. */
data class GenericDraft(
    val description: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
) {
    fun toPassFields(): List<PassField> = buildList {
        if (description.isNotBlank()) add(PassField("Info", description, FieldPosition.PRIMARY))
        date?.let { add(PassField("Date", it.format(MANUAL_PASS_DATE_FORMAT), FieldPosition.AUXILIARY)) }
        time?.let { add(PassField("Time", it.format(MANUAL_PASS_TIME_FORMAT), FieldPosition.AUXILIARY)) }
    }

    fun toRelevantDate(): Instant? = combineDateTime(date, time)
}
