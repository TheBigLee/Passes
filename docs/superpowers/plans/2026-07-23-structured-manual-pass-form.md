# Structured Manual Pass Form Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the barcode-value-only manual pass creation form with a structured, kind-aware form (Event/Boarding/Loyalty/Generic) so manually-created passes carry identifying text and date info, and render through the same layouts as imported passes.

**Architecture:** A new `ManualPassDrafts.kt` holds pure, Compose-free data classes (one per kind) with extension functions that turn form input into `PassField`/`Instant?`/`TransitType?` - fully unit-testable without Robolectric. A new `ManualPassFields.kt` holds the per-kind Compose field composables plus shared date/time picker helpers. `CreatePassScreen.kt` gets a kind picker and wires the two together; `PassRepository.createManualPass` gains parameters to build a fully-formed `Pass`.

**Tech Stack:** Kotlin, Jetpack Compose (Material3 `SegmentedButton`, `DatePicker`, `TimePicker` - all already available in this project's Compose BOM), `java.time`, JUnit (plain, no Robolectric needed for the new pure-logic tests - consistent with how `PkPassImporterTest.kt` already tests `java.time.Instant` logic without `@RunWith`).

---

### Task 1: Manual pass draft models and field-assembly logic

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/ui/ManualPassDrafts.kt`
- Test: `app/src/test/java/ch/bigli/passes/ui/ManualPassDraftsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/ch/bigli/passes/ui/ManualPassDraftsTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "ch.bigli.passes.ui.ManualPassDraftsTest"`
Expected: FAIL to compile - `EventDraft`, `BoardingDraft`, `LoyaltyDraft`, `GenericDraft` are unresolved references.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/ch/bigli/passes/ui/ManualPassDrafts.kt`:

```kotlin
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew testDebugUnitTest --tests "ch.bigli.passes.ui.ManualPassDraftsTest"`
Expected: PASS (12 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/ManualPassDrafts.kt app/src/test/java/ch/bigli/passes/ui/ManualPassDraftsTest.kt
git commit -m "Add manual-pass draft models with field-assembly logic"
```

---

### Task 2: Extend PassRepository.createManualPass

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`
- Modify: `app/src/main/java/ch/bigli/passes/MainActivity.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassRepositoryManualTest.kt`

- [ ] **Step 1: Write the failing test**

Replace the contents of `app/src/test/java/ch/bigli/passes/data/PassRepositoryManualTest.kt`:

```kotlin
package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import ch.bigli.passes.domain.TransitType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class PassRepositoryManualTest {
    private lateinit var db: PassDatabase
    private lateinit var repo: PassRepository

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao())
    }

    @After fun tearDown() = db.close()

    @Test fun `createManualPass stores a GENERIC pass with no extra fields`() = runTest {
        val pass = repo.createManualPass(
            type = PassType.GENERIC,
            organization = "Acme",
            fields = emptyList(),
            relevantDate = null,
            transitType = null,
            barcodeFormat = BarcodeFormat.CODE128,
            barcodeValue = "6001234567890",
        )
        val stored = repo.getById(pass.id)!!
        assertEquals(PassType.GENERIC, stored.type)
        assertEquals(SourceFormat.MANUAL, stored.sourceFormat)
        assertEquals("Acme", stored.organization)
        assertEquals("Acme", stored.subtitle)
        assertEquals(BarcodeFormat.CODE128, stored.barcode!!.format)
        assertEquals("6001234567890", stored.barcode!!.message)
        assertEquals("", stored.rawFilePath)
        assertNull(stored.transitType)
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun `createManualPass stores BOARDING fields, relevantDate, and transitType`() = runTest {
        val fields = listOf(
            PassField("Departure", "Zurich", FieldPosition.PRIMARY),
            PassField("Arrival", "Warsaw", FieldPosition.PRIMARY),
        )
        val relevantDate = Instant.parse("2026-08-15T08:45:00Z")
        val pass = repo.createManualPass(
            type = PassType.BOARDING,
            organization = "SWISS",
            fields = fields,
            relevantDate = relevantDate,
            transitType = TransitType.AIR,
            barcodeFormat = BarcodeFormat.QR,
            barcodeValue = "SWISS123",
        )
        val stored = repo.getById(pass.id)!!
        assertEquals(PassType.BOARDING, stored.type)
        assertEquals(fields, stored.fields)
        assertEquals(relevantDate, stored.relevantDate)
        assertEquals(TransitType.AIR, stored.transitType)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --tests "ch.bigli.passes.data.PassRepositoryManualTest"`
Expected: FAIL to compile - `createManualPass` doesn't accept these named arguments yet.

- [ ] **Step 3: Update PassRepository.createManualPass**

In `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`, add the import:

```kotlin
import ch.bigli.passes.domain.TransitType
```

(add it alphabetically next to the other `ch.bigli.passes.domain.*` imports) and

```kotlin
import java.time.Instant
```

(add it alphabetically next to `java.io.File`).

Replace the existing `createManualPass` function:

```kotlin
    /** Creates a pass from a manually-entered or scanned barcode (no source file). */
    suspend fun createManualPass(format: BarcodeFormat, value: String): Pass =
        withContext(Dispatchers.IO) {
            val pass = Pass(
                id = UUID.randomUUID().toString(),
                type = PassType.GENERIC,
                subtitle = null,
                organization = null,
                bgColor = null,
                fgColor = null,
                fields = emptyList(),
                barcode = Barcode(format, value, null),
                relevantDate = null,
                rawFilePath = "",
                sourceFormat = SourceFormat.MANUAL,
                updateInfo = null,
            )
            dao.insert(pass.toEntity())
            pass
        }
```

with:

```kotlin
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
```

- [ ] **Step 4: Fix the now-broken call site in MainActivity.kt**

`CreatePassScreen` still has its old 2-argument callback at this point (it's rewired in Task 4), so give the repository call explicit placeholder values that keep everything compiling:

In `app/src/main/java/ch/bigli/passes/MainActivity.kt`, add the import:

```kotlin
import ch.bigli.passes.domain.PassType
```

(alphabetically next to `import ch.bigli.passes.domain.Pass`), then replace:

```kotlin
                onCreate = { format, value ->
                    scope.launch {
                        val pass = repo.createManualPass(format, value)
                        app.pendingScan.value = null
                        nav.navigate("detail/${pass.id}") { popUpTo("list") }
                    }
                },
```

with:

```kotlin
                onCreate = { format, value ->
                    scope.launch {
                        val pass = repo.createManualPass(
                            type = PassType.GENERIC,
                            organization = "",
                            fields = emptyList(),
                            relevantDate = null,
                            transitType = null,
                            barcodeFormat = format,
                            barcodeValue = value,
                        )
                        app.pendingScan.value = null
                        nav.navigate("detail/${pass.id}") { popUpTo("list") }
                    }
                },
```

- [ ] **Step 5: Run test to verify it passes, and full suite for regressions**

Run: `./gradlew testDebugUnitTest --tests "ch.bigli.passes.data.PassRepositoryManualTest"`
Expected: PASS (2 tests)

Run: `./gradlew testDebugUnitTest`
Expected: PASS (all tests - this also confirms `MainActivity.kt` and `PassRepository.kt` still compile together)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/data/PassRepository.kt app/src/main/java/ch/bigli/passes/MainActivity.kt app/src/test/java/ch/bigli/passes/data/PassRepositoryManualTest.kt
git commit -m "Extend createManualPass to build a fully-formed structured Pass"
```

---

### Task 3: Per-kind Compose field forms

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/ui/ManualPassFields.kt`

This task has no automated tests (Compose UI, no test infra in this project - see spec's Testing section). It will be exercised end-to-end once Task 4 wires it into `CreatePassScreen`.

- [ ] **Step 1: Create the shared date/time picker fields and per-kind forms**

Create `app/src/main/java/ch/bigli/passes/ui/ManualPassFields.kt`:

```kotlin
@file:OptIn(ExperimentalMaterial3Api::class)

package ch.bigli.passes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.bigli.passes.domain.TransitType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A read-only text field that opens a [DatePickerDialog] on tap. */
@Composable
private fun DateField(label: String, date: LocalDate?, onDateChange: (LocalDate) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = date?.format(MANUAL_PASS_DATE_FORMAT) ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = OutlinedTextFieldDefaults.colors().unfocusedTextColor,
                disabledBorderColor = OutlinedTextFieldDefaults.colors().unfocusedIndicatorColor,
                disabledLabelColor = OutlinedTextFieldDefaults.colors().unfocusedLabelColor,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { showPicker = true })
    }
    if (showPicker) {
        // DatePickerState works in UTC start-of-day millis - converting back with
        // ZoneOffset.UTC (not the system zone) avoids landing on the wrong day.
        val initialMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** A read-only text field that opens a [TimePicker] wrapped in an [AlertDialog] on tap. */
@Composable
private fun TimeField(label: String, time: LocalTime?, onTimeChange: (LocalTime) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = time?.format(MANUAL_PASS_TIME_FORMAT) ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(label) },
            colors = OutlinedTextFieldDefaults.colors(
                disabledTextColor = OutlinedTextFieldDefaults.colors().unfocusedTextColor,
                disabledBorderColor = OutlinedTextFieldDefaults.colors().unfocusedIndicatorColor,
                disabledLabelColor = OutlinedTextFieldDefaults.colors().unfocusedLabelColor,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { showPicker = true })
    }
    if (showPicker) {
        val state = rememberTimePickerState(
            initialHour = time?.hour ?: 12,
            initialMinute = time?.minute ?: 0,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(state.hour, state.minute))
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } },
            text = { TimePicker(state = state) },
        )
    }
}

@Composable
fun EventFields(draft: EventDraft, onDraftChange: (EventDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.eventName,
            onValueChange = { onDraftChange(draft.copy(eventName = it)) },
            label = { Text("Event name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.location,
            onValueChange = { onDraftChange(draft.copy(location = it)) },
            label = { Text("Location") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField("Date", draft.date) { onDraftChange(draft.copy(date = it)) }
        TimeField("Time", draft.time) { onDraftChange(draft.copy(time = it)) }
    }
}

@Composable
fun BoardingFields(draft: BoardingDraft, onDraftChange: (BoardingDraft) -> Unit) {
    var transitExpanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.from,
            onValueChange = { onDraftChange(draft.copy(from = it)) },
            label = { Text("From") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.to,
            onValueChange = { onDraftChange(draft.copy(to = it)) },
            label = { Text("To") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ExposedDropdownMenuBox(expanded = transitExpanded, onExpandedChange = { transitExpanded = it }) {
            OutlinedTextField(
                value = draft.transitType.name,
                onValueChange = {},
                readOnly = true,
                label = { Text("Transit mode") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = transitExpanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = transitExpanded, onDismissRequest = { transitExpanded = false }) {
                TransitType.entries.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(t.name) },
                        onClick = { onDraftChange(draft.copy(transitType = t)); transitExpanded = false },
                    )
                }
            }
        }
        DateField("Date", draft.date) { onDraftChange(draft.copy(date = it)) }
        TimeField("Boards", draft.time) { onDraftChange(draft.copy(time = it)) }
    }
}

@Composable
fun LoyaltyFields(draft: LoyaltyDraft, onDraftChange: (LoyaltyDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.memberName,
            onValueChange = { onDraftChange(draft.copy(memberName = it)) },
            label = { Text("Member name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.details,
            onValueChange = { onDraftChange(draft.copy(details = it)) },
            label = { Text("Details") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun GenericFieldsForm(draft: GenericDraft, onDraftChange: (GenericDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = draft.description,
            onValueChange = { onDraftChange(draft.copy(description = it)) },
            label = { Text("Description") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        DateField("Date", draft.date) { onDraftChange(draft.copy(date = it)) }
        TimeField("Time", draft.time) { onDraftChange(draft.copy(time = it)) }
    }
}
```

Note: `DateField`/`TimeField` reference `MANUAL_PASS_DATE_FORMAT`/`MANUAL_PASS_TIME_FORMAT`, the `internal val`s defined in `ManualPassDrafts.kt` (Task 1) - same module, no import needed since both files are in package `ch.bigli.passes.ui`.

`ExposedDropdownMenu` (used inside `BoardingFields`) is a member of `ExposedDropdownMenuBoxScope`, resolved implicitly via the lambda receiver - no separate import needed, matching the existing pattern already used for the barcode format dropdown in `CreatePassScreen.kt`.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL (these composables are unused until Task 4, which is fine - no unused-code compile errors in Kotlin, only lint warnings)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/ManualPassFields.kt
git commit -m "Add per-kind Compose field forms for manual pass creation"
```

---

### Task 4: Wire the kind picker into CreatePassScreen

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/CreatePassScreen.kt`
- Modify: `app/src/main/java/ch/bigli/passes/MainActivity.kt`

- [ ] **Step 1: Rewrite CreatePassScreen.kt**

Replace the entire contents of `app/src/main/java/ch/bigli/passes/ui/CreatePassScreen.kt`:

```kotlin
package ch.bigli.passes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.TransitType
import java.time.Instant

private val MANUAL_PASS_KINDS = listOf(PassType.EVENT, PassType.BOARDING, PassType.LOYALTY, PassType.GENERIC)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreatePassScreen(
    prefill: Barcode?,
    onCreate: (
        type: PassType,
        organization: String,
        fields: List<PassField>,
        relevantDate: Instant?,
        transitType: TransitType?,
        barcodeFormat: BarcodeFormat,
        barcodeValue: String,
    ) -> Unit,
    onBack: () -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(PassType.EVENT) }
    var organization by rememberSaveable { mutableStateOf("") }
    var eventDraft by remember { mutableStateOf(EventDraft()) }
    var boardingDraft by remember { mutableStateOf(BoardingDraft()) }
    var loyaltyDraft by remember { mutableStateOf(LoyaltyDraft()) }
    var genericDraft by remember { mutableStateOf(GenericDraft()) }

    var value by rememberSaveable { mutableStateOf(prefill?.message ?: "") }
    var format by remember { mutableStateOf(prefill?.format ?: BarcodeFormat.QR) }
    var expanded by remember { mutableStateOf(false) }

    val kindIsValid = if (kind == PassType.BOARDING) boardingDraft.isValid else true
    val canSubmit = organization.isNotBlank() && value.isNotBlank() && kindIsValid

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New pass") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                MANUAL_PASS_KINDS.forEachIndexed { index, k ->
                    SegmentedButton(
                        selected = kind == k,
                        onClick = { kind = k },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = MANUAL_PASS_KINDS.size),
                    ) { Text(k.name) }
                }
            }
            OutlinedTextField(
                value = organization,
                onValueChange = { organization = it },
                label = { Text("Organization") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            when (kind) {
                PassType.EVENT -> EventFields(eventDraft) { eventDraft = it }
                PassType.BOARDING -> BoardingFields(boardingDraft) { boardingDraft = it }
                PassType.LOYALTY -> LoyaltyFields(loyaltyDraft) { loyaltyDraft = it }
                else -> GenericFieldsForm(genericDraft) { genericDraft = it }
            }
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Barcode value") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = format.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Format") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    BarcodeFormat.entries.forEach { fmt ->
                        DropdownMenuItem(
                            text = { Text(fmt.name) },
                            onClick = { format = fmt; expanded = false },
                        )
                    }
                }
            }
            Button(
                onClick = {
                    // Explicit type args on every branch - without them, Kotlin can't always
                    // infer a common Triple<List<PassField>, Instant?, TransitType?> type
                    // across a when-expression mixing concrete values and bare nulls.
                    val (fields, relevantDate, transitType) = when (kind) {
                        PassType.EVENT -> Triple<List<PassField>, Instant?, TransitType?>(eventDraft.toPassFields(), eventDraft.toRelevantDate(), null)
                        PassType.BOARDING -> Triple<List<PassField>, Instant?, TransitType?>(boardingDraft.toPassFields(), boardingDraft.toRelevantDate(), boardingDraft.transitType)
                        PassType.LOYALTY -> Triple<List<PassField>, Instant?, TransitType?>(loyaltyDraft.toPassFields(), null, null)
                        else -> Triple<List<PassField>, Instant?, TransitType?>(genericDraft.toPassFields(), genericDraft.toRelevantDate(), null)
                    }
                    onCreate(kind, organization, fields, relevantDate, transitType, format, value)
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create pass") }
        }
    }
}
```

- [ ] **Step 2: Update the call site in MainActivity.kt**

In `app/src/main/java/ch/bigli/passes/MainActivity.kt`, remove the now-unnecessary `PassType` import added in Task 2 (it's no longer referenced by name here) and replace the `"create"` composable's `onCreate` lambda:

```kotlin
                onCreate = { format, value ->
                    scope.launch {
                        val pass = repo.createManualPass(
                            type = PassType.GENERIC,
                            organization = "",
                            fields = emptyList(),
                            relevantDate = null,
                            transitType = null,
                            barcodeFormat = format,
                            barcodeValue = value,
                        )
                        app.pendingScan.value = null
                        nav.navigate("detail/${pass.id}") { popUpTo("list") }
                    }
                },
```

with:

```kotlin
                onCreate = { type, organization, fields, relevantDate, transitType, barcodeFormat, barcodeValue ->
                    scope.launch {
                        val pass = repo.createManualPass(
                            type = type,
                            organization = organization,
                            fields = fields,
                            relevantDate = relevantDate,
                            transitType = transitType,
                            barcodeFormat = barcodeFormat,
                            barcodeValue = barcodeValue,
                        )
                        app.pendingScan.value = null
                        nav.navigate("detail/${pass.id}") { popUpTo("list") }
                    }
                },
```

- [ ] **Step 3: Run the full test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (all tests, including Task 1 and Task 2's new tests)

- [ ] **Step 4: Install and manually verify on-device**

Run: `./gradlew installDebug`

Manually verify each kind end-to-end (tap the "+" button on the pass list, or scan a barcode first):
- **Event:** pick Event, fill Organization + Event name + Location, set a date and time via the pickers, fill a barcode value, tap Create. Confirm the new pass shows the event name large on the front, Location/Date/Time below, and the organization name in the top bar.
- **Boarding:** pick Boarding, fill Organization + From + To, pick a transit mode (e.g. Train), set date/time, tap Create. Confirm it renders with the two-up departure/arrival row and the matching transit icon (from #19).
- **Boarding validation:** pick Boarding, leave From or To blank - confirm the Create button stays disabled.
- **Loyalty:** pick Loyalty, fill Organization + Member name + Details, tap Create. Confirm Member/Details render as fields.
- **Generic:** pick Generic, fill Organization + Description, tap Create. Confirm Description renders as the primary line.
- Confirm the pass list sorts a pass with a set date/time correctly relative to passes without one.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/CreatePassScreen.kt app/src/main/java/ch/bigli/passes/MainActivity.kt
git commit -m "Wire kind picker and structured fields into CreatePassScreen"
```

---

### Task 5: Wrap up

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Update the README feature list**

In `README.md`, find the `## Features` section and the `- **Manual entry**` bullet:

```markdown
- **Manual entry** — type in or scan a barcode/QR directly.
```

Replace it with:

```markdown
- **Manual entry** — type in or scan a barcode/QR directly, with a structured form (pick Event/Boarding/Loyalty/Generic, fill in kind-specific fields like location, date/time, or transit mode) so manually-created passes render just like imported ones.
```

- [ ] **Step 2: Run the full test suite one more time**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (all tests)

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "Document the structured manual pass form in the README"
```

- [ ] **Step 4: Use superpowers:finishing-a-development-branch to wrap up the branch**
