# Pass Field Layout Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Render `Pass.fields` according to their real `FieldPosition` (HEADER/PRIMARY/SECONDARY/AUXILIARY) instead of pooling everything but PRIMARY into one capped `take(4)` row, fixing GitHub issues #12 (primary values never shown) and #13 (non-primary fields silently truncated).

**Architecture:** A new file `PassFieldLayouts.kt` holds two composables — `GenericFieldsLayout` (EVENT/LOYALTY/COUPON/GENERIC: full-width stacked primaries, then flowing secondary/auxiliary fields, no cap) and `BoardingFieldsLayout` + `BoardingHeaderRow` (BOARDING only: top-right header slot, big two-up primary row with label/value sizing inverted and a plane icon, then flowing secondary/auxiliary rows). `PassDetailScreen.kt`'s `PassFrontContent` picks between them by `pass.type`. `PassListScreen.kt`'s one-line card summary gets a small matching fix so it includes the primary field's value.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), `androidx.compose.foundation.layout.FlowRow` (stable since foundation 1.7, confirmed present in this project's resolved 1.7.2 dependency — no `@OptIn` needed), `Icons.Filled.Flight` (confirmed present in `material-icons-extended`).

---

### Task 1: `GenericFieldsLayout` composable

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/ui/PassFieldLayouts.kt`

- [ ] **Step 1: Create the file with `GenericFieldsLayout`**

```kotlin
package ch.bigli.passes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField

/**
 * Field layout for every pass type except BOARDING (which uses [BoardingFieldsLayout]).
 * Stacks PRIMARY fields full-width and large, then flows SECONDARY/AUXILIARY fields below -
 * nothing is capped or dropped, matching how Google Wallet renders an event ticket.
 */
@Composable
fun GenericFieldsLayout(pass: Pass, fg: Color) {
    val primary = pass.fields.filter { it.position == FieldPosition.PRIMARY }
    val secondary = pass.fields.filter { it.position == FieldPosition.SECONDARY }
    val auxiliary = pass.fields.filter { it.position == FieldPosition.AUXILIARY }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        primary.forEach { f ->
            Column(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
                Text(f.value, color = fg, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
        if (secondary.isNotEmpty() || auxiliary.isNotEmpty()) {
            Spacer(Modifier.size(16.dp))
        }
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            (secondary + auxiliary).forEach { f -> GenericField(f, fg) }
        }
    }
}

@Composable
private fun GenericField(field: PassField, fg: Color) {
    Column(
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(field.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
        Text(field.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
```

Note: `Text` here is `androidx.compose.material3.Text` - add the import:

```kotlin
import androidx.compose.material3.Text
```

Put it alphabetically among the other imports (after the `androidx.compose.foundation.layout.*` block, before `androidx.compose.runtime.Composable`), so the final import block reads:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField
```

- [ ] **Step 2: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (this file isn't wired into any screen yet, so it can't be visually verified until Task 3 - a clean compile is the only checkpoint here)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/PassFieldLayouts.kt
git commit -m "Add GenericFieldsLayout for non-boarding pass field display"
```

---

### Task 2: `BoardingFieldsLayout` and `BoardingHeaderRow` composables

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassFieldLayouts.kt`

- [ ] **Step 1: Add imports**

Add to the top of `PassFieldLayouts.kt` (merge alphabetically with the existing import block):

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
```

(`size` and `Alignment` are already imported from Task 1 - don't duplicate the import line, just make sure `Row`, `Icons`, `Icons.Filled.Flight`, and `Icon` are present.)

- [ ] **Step 2: Add `BoardingHeaderRow`, `BoardingFieldsLayout`, and their private helpers**

Append to `PassFieldLayouts.kt`:

```kotlin
/**
 * Top-right header slot for BOARDING passes (e.g. "BOARDS 08:45"), rendered next to the
 * strip/logo area rather than inside the scrollable fields column. No-op if the pass has
 * no HEADER fields (most non-boarding sample passes, and some boarding passes, have none).
 */
@Composable
fun BoardingHeaderRow(pass: Pass, fg: Color) {
    val header = pass.fields.filter { it.position == FieldPosition.HEADER }
    if (header.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        header.forEach { f ->
            Column(
                Modifier.padding(start = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 9.sp)
                Text(f.value, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Field layout for BOARDING passes, matching the SWISS/Google Wallet reference: a big
 * two-up PRIMARY row (label big / value small - the inverse of every other position,
 * since boardingPass primary fields carry the airport code in `label` and the city name
 * in `value`) with a plane icon between, then SECONDARY and AUXILIARY fields flowing
 * below with no cap.
 */
@Composable
fun BoardingFieldsLayout(pass: Pass, fg: Color) {
    val primary = pass.fields.filter { it.position == FieldPosition.PRIMARY }.take(2)
    val secondary = pass.fields.filter { it.position == FieldPosition.SECONDARY }
    val auxiliary = pass.fields.filter { it.position == FieldPosition.AUXILIARY }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (primary.size == 2) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BoardingPrimaryField(primary[0], fg)
                Icon(Icons.Filled.Flight, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
                BoardingPrimaryField(primary[1], fg)
            }
        } else if (primary.size == 1) {
            BoardingPrimaryField(primary[0], fg)
            Spacer(Modifier.size(16.dp))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            secondary.forEach { BoardingSecondaryField(it, fg) }
        }
        if (auxiliary.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                auxiliary.forEach { BoardingSecondaryField(it, fg) }
            }
        }
    }
}

@Composable
private fun BoardingPrimaryField(field: PassField, fg: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(field.label, color = fg, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(field.value, color = fg.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun BoardingSecondaryField(field: PassField, fg: Color) {
    Column(
        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(field.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
        Text(field.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
```

- [ ] **Step 3: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/PassFieldLayouts.kt
git commit -m "Add BoardingFieldsLayout and BoardingHeaderRow for boarding pass field display"
```

---

### Task 3: Wire the new layouts into `PassDetailScreen.kt`

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt:97` (imports), `:421-429` (strip/header area), `:435-442` (fields row)

- [ ] **Step 1: Add the `PassType` import**

In `PassDetailScreen.kt`, change:

```kotlin
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
```

to:

```kotlin
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassType
```

- [ ] **Step 2: Render `BoardingHeaderRow` next to the strip**

Find this block (currently lines 421-429):

```kotlin
        val isVoidedOrExpired = pass.isVoidedOrExpired()
        Column(Modifier.fillMaxSize().background(bg)) {
            strip?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
```

Replace it with:

```kotlin
        val isVoidedOrExpired = pass.isVoidedOrExpired()
        Column(Modifier.fillMaxSize().background(bg)) {
            strip?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
            if (pass.type == PassType.BOARDING) {
                BoardingHeaderRow(pass, fg)
            }
```

- [ ] **Step 3: Replace the pooled fields row with the type-based layout choice**

Find this block (currently lines 435-442):

```kotlin
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    pass.fields.filter { it.position != FieldPosition.PRIMARY }.take(4).forEach { f ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
                            Text(f.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
```

Replace it with:

```kotlin
                if (pass.type == PassType.BOARDING) {
                    BoardingFieldsLayout(pass, fg)
                } else {
                    GenericFieldsLayout(pass, fg)
                }
```

- [ ] **Step 4: Remove the now-unused `FieldPosition` import if nothing else in the file uses it**

Run: `grep -n "FieldPosition" app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt`
Expected: no matches other than the `import` line itself - if so, delete the `import ch.bigli.passes.domain.FieldPosition` line. If there's another usage elsewhere in the file, leave the import in place.

- [ ] **Step 5: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt
git commit -m "Render pass fields by position instead of pooling into one capped row"
```

---

### Task 4: Fix `PassListScreen.kt`'s card summary to include the primary field value

**Files:**
- Modify: `app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt:161-165`

- [ ] **Step 1: Add a primary-value line above the existing summary**

Find this block (currently lines 161-165):

```kotlin
        val summary = pass.fields.filter { it.position != FieldPosition.PRIMARY }
            .take(3).joinToString("   ") { "${it.label}: ${it.value}" }
        if (summary.isNotEmpty()) {
            Text(summary, color = fg.copy(alpha = 0.9f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
```

Replace it with:

```kotlin
        val primaryValue = pass.fields.firstOrNull { it.position == FieldPosition.PRIMARY }?.value
        primaryValue?.let {
            Text(it, color = fg, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp))
        }
        val summary = pass.fields.filter { it.position != FieldPosition.PRIMARY }
            .take(3).joinToString("   ") { "${it.label}: ${it.value}" }
        if (summary.isNotEmpty()) {
            Text(summary, color = fg.copy(alpha = 0.9f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
```

(The second `Text`'s top padding drops from 8.dp to 4.dp only when a primary value is also shown above it - but since both `Text` calls are static in the composable regardless of whether `primaryValue` is null, top padding is always 4.dp between the two lines when both are present, and 8.dp is preserved as the gap above whichever line renders first if `primaryValue` is null. This matches the existing card's spacing rhythm without introducing a new spacing constant.)

- [ ] **Step 2: Compile-check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt
git commit -m "Show primary field value in pass list card summary"
```

---

### Task 5: On-device verification

**Files:** none (manual verification only - this repo has no Compose UI test harness; see the design doc's Testing section)

- [ ] **Step 1: Build and install the debug APK**

Run: `./gradlew :app:installDebug`
Expected: `BUILD SUCCESSFUL`, app installed on the connected device

- [ ] **Step 2: Import and check `sample-passes/boarding-air.pkpass`**

Import this file into the app (via the existing import flow) and open its detail screen. Confirm:
- Top-right header slot shows `BOARDS 08:45` next to the strip/logo.
- Big primary row shows `JFK` (label, large) above `New York` (value, small) twice, with a plane icon between - both origin and destination.
- A secondary row shows `GATE: A12` and `SEAT: 14C`.
- An auxiliary row shows `DATE: 15 Aug` and `CLASS: Economy`.
- Nothing is cut off or missing.

- [ ] **Step 3: Import and check `sample-passes/event-ticket.pkpass`**

Import this file and open its detail screen. Confirm:
- No top-right header slot (HEADER is deferred for non-boarding types) - `DOORS 18:30` is not shown, which is expected for this iteration.
- `EVENT: Basel Tattoo` renders full-width, large.
- `DATE: 18 Jul 2026` and `TIME: 20:00` render as a flowing row below.
- `SECTION: B`, `ROW: 12`, `SEAT: 5` render as a flowing row below that - all three visible, none dropped.

- [ ] **Step 4: Check the list screen**

Go back to the pass list. Confirm both cards now show a line with the primary field's value (`New York` for the boarding pass, `Basel Tattoo` for the event ticket) above the existing secondary-field summary line.

- [ ] **Step 5: Report result**

If all checks pass, this task is done - no commit needed (no code changed in this task). If any check fails, note exactly what's wrong and return to the relevant task above to fix it before proceeding.

---

### Task 6: Update README

**Files:**
- Modify: `README.md`

- [ ] **Step 1: Add a bullet describing the new field layout behavior**

Find the existing bullet (near line 11 per earlier grep):

```
- **Flip-to-back detail view** — a persistent info icon flips the pass to reveal pkpass `backFields` (with HTML/link rendering) and Delete.
```

Add a new bullet directly after it:

```
- **Type-aware field layout** — boarding passes get a Wallet-style layout (big origin/destination row with a plane icon, header/secondary/auxiliary fields in their own rows); every other pass type stacks its primary field(s) large, then flows secondary/auxiliary fields below with nothing capped or dropped.
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: document type-aware pass field layout"
```
