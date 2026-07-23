package ch.bigli.passes.ui

import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.TransitType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private data class PassFieldExpectation(val label: String, val value: String, val position: FieldPosition)
private fun PassField.toExpectation() = PassFieldExpectation(label, value, position)

class ManualPassDraftsTest {
    @Test fun `EventDraft builds a field only for the non-blank event name`() {
        val draft = EventDraft(eventName = "Basel Tattoo")
        val fields = draft.toPassFields()
        assertEquals(1, fields.size)
        assertEquals(PassFieldExpectation("Event", "Basel Tattoo", FieldPosition.PRIMARY), fields[0].toExpectation())
    }

    @Test fun `EventDraft includes location date and time when present`() {
        val draft = EventDraft(
            eventName = "Basel Tattoo",
            location = "Kaserne",
            date = LocalDate.of(2026, 7, 18),
            time = LocalTime.of(20, 0),
        )
        val fields = draft.toPassFields()
        assertEquals(4, fields.size)
        assertEquals(PassFieldExpectation("Location", "Kaserne", FieldPosition.SECONDARY), fields[1].toExpectation())
        assertEquals(PassFieldExpectation("Date", "18 Jul 2026", FieldPosition.AUXILIARY), fields[2].toExpectation())
        assertEquals(PassFieldExpectation("Time", "20:00", FieldPosition.AUXILIARY), fields[3].toExpectation())
    }

    @Test fun `EventDraft combines date and time into relevantDate using the system zone`() {
        val draft = EventDraft(eventName = "X", date = LocalDate.of(2026, 7, 18), time = LocalTime.of(20, 0))
        val expected = LocalDate.of(2026, 7, 18).atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant()
        assertEquals(expected, draft.toRelevantDate())
    }

    @Test fun `EventDraft relevantDate is null unless both date and time are set`() {
        assertNull(EventDraft(eventName = "X", date = LocalDate.of(2026, 7, 18)).toRelevantDate())
        assertNull(EventDraft(eventName = "X", time = LocalTime.of(20, 0)).toRelevantDate())
        assertNull(EventDraft(eventName = "X").toRelevantDate())
    }

    @Test fun `BoardingDraft builds departure and arrival as PRIMARY fields`() {
        val draft = BoardingDraft(from = "Zurich", to = "Warsaw", transitType = TransitType.AIR)
        val fields = draft.toPassFields()
        assertEquals(2, fields.size)
        assertEquals(PassFieldExpectation("Departure", "Zurich", FieldPosition.PRIMARY), fields[0].toExpectation())
        assertEquals(PassFieldExpectation("Arrival", "Warsaw", FieldPosition.PRIMARY), fields[1].toExpectation())
    }

    @Test fun `BoardingDraft renders its time field labeled Boards`() {
        val draft = BoardingDraft(from = "A", to = "B", time = LocalTime.of(8, 45))
        val fields = draft.toPassFields()
        assertEquals(PassFieldExpectation("Boards", "08:45", FieldPosition.AUXILIARY), fields.last().toExpectation())
    }

    @Test fun `BoardingDraft isValid requires both from and to`() {
        assertFalse(BoardingDraft().isValid)
        assertFalse(BoardingDraft(from = "A").isValid)
        assertFalse(BoardingDraft(to = "B").isValid)
        assertTrue(BoardingDraft(from = "A", to = "B").isValid)
    }

    @Test fun `LoyaltyDraft builds member and details as AUXILIARY fields`() {
        val draft = LoyaltyDraft(memberName = "Nicolas B.", details = "1240 points")
        val fields = draft.toPassFields()
        assertEquals(2, fields.size)
        assertEquals(PassFieldExpectation("Member", "Nicolas B.", FieldPosition.AUXILIARY), fields[0].toExpectation())
        assertEquals(PassFieldExpectation("Details", "1240 points", FieldPosition.AUXILIARY), fields[1].toExpectation())
    }

    @Test fun `LoyaltyDraft omits blank fields entirely`() {
        assertEquals(emptyList<PassField>(), LoyaltyDraft().toPassFields())
    }

    @Test fun `GenericDraft builds description as a PRIMARY field`() {
        val draft = GenericDraft(description = "Gym Membership")
        val fields = draft.toPassFields()
        assertEquals(PassFieldExpectation("Info", "Gym Membership", FieldPosition.PRIMARY), fields[0].toExpectation())
    }

    @Test fun `GenericDraft with nothing filled in produces no fields and no relevantDate`() {
        assertEquals(emptyList<PassField>(), GenericDraft().toPassFields())
        assertNull(GenericDraft().toRelevantDate())
    }
}
