# Pass Wallet — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A usable single-format Android wallet: import an Apple `.pkpass` file, store it, list passes, and open one full-screen with its barcode rendered large and the screen brightened for scanning.

**Architecture:** One-way funnel — a `PkPassImporter` parses `.pkpass` into a format-agnostic `Pass` domain model, `PassRepository` persists it (Room index + raw file on disk), and Compose screens (list → detail) render it. Storage and UI never know the source format.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room, ZXing (`core` for encoding), Kotlin coroutines/Flow, JUnit + Robolectric for JVM unit tests, kotlinx-serialization for `pass.json`.

---

## Prerequisites (one-time, not a coding task)

Android Studio is already installed on this machine and the SDK is at
`/home/bigli/Android/Sdk`. Only one setup step is needed:

- Create `local.properties` at repo root (gitignored) with:
  `sdk.dir=/home/bigli/Android/Sdk`
- Ensure SDK packages `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`
  are installed (via Studio's SDK Manager, or `sdkmanager` in the SDK's `cmdline-tools`).
- JDK 17+ required (JDK 26 present on this machine is fine).
- Easiest path: open the project in Android Studio, let it sync Gradle, and run/test
  from there. Command-line `./gradlew` works too once `local.properties` exists.

Barcode rendering and pkpass parsing are pure-JVM and unit-testable without a device/emulator (Robolectric). A device/emulator is only needed for manual end-to-end verification.

---

## File Structure

```
settings.gradle.kts
build.gradle.kts                      (root)
gradle/libs.versions.toml             (version catalog)
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/ch/bigli/passes/
    domain/Pass.kt                    Pass, PassField, Barcode + enums
    domain/ImportError.kt             sealed import errors
    importing/PassImporter.kt         interface
    importing/PkPassImporter.kt       .pkpass → Pass
    importing/PkPassJson.kt           kotlinx-serialization DTOs for pass.json
    barcode/BarcodeRenderer.kt        Barcode → Bitmap (ZXing)
    data/PassEntity.kt                Room entity + mappers
    data/PassDao.kt                   Room DAO
    data/PassDatabase.kt              RoomDatabase
    data/PassRepository.kt            import + query + file storage
    ui/PassListScreen.kt             Compose list
    ui/PassListViewModel.kt
    ui/PassDetailScreen.kt           Compose detail + brightness
    ui/PassDetailViewModel.kt
    ui/theme/Theme.kt                 Material theme
    MainActivity.kt                   NavHost, import launcher
    PassApp.kt                        Application (DI wiring)
app/src/test/java/ch/bigli/passes/    JVM unit tests (Robolectric where needed)
app/src/test/resources/fixtures/      sample.pkpass and malformed fixtures
```

Split by responsibility. `domain` is pure Kotlin (no Android imports) so it is trivially testable. `importing` depends only on `domain`. `data` and `barcode` are the Android-touching leaves. `ui` composes them.

---

## Task 0: Project scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/ch/bigli/passes/MainActivity.kt`, `app/src/main/java/ch/bigli/passes/ui/theme/Theme.kt`

- [ ] **Step 1: Version catalog** — Create `gradle/libs.versions.toml`

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
compose-bom = "2024.09.02"
activity-compose = "1.9.2"
lifecycle = "2.8.6"
room = "2.6.1"
ksp = "2.0.20-1.0.25"
zxing = "3.5.3"
serialization = "1.7.3"
navigation = "2.8.1"
junit = "4.13.2"
robolectric = "4.13"
coroutines-test = "1.9.0"
androidx-test-core = "1.6.1"

[libraries]
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity-compose" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
zxing-core = { module = "com.google.zxing:core", version.ref = "zxing" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines-test" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-test-core" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 2: Root settings + build** — Create `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "Passes"
include(":app")
```

Create `build.gradle.kts` (root):

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 3: App module build** — Create `app/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ch.bigli.passes"
    compileSdk = 34

    defaultConfig {
        applicationId = "ch.bigli.passes"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
}
```

- [ ] **Step 4: Manifest** — Create `app/src/main/AndroidManifest.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:label="Passes"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 5: Minimal theme + activity** — Create `app/src/main/java/ch/bigli/passes/ui/theme/Theme.kt`

```kotlin
package ch.bigli.passes.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import android.os.Build

@Composable
fun PassesTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= 31) {
        val ctx = LocalContext.current
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
```

Create `app/src/main/java/ch/bigli/passes/MainActivity.kt` (temporary placeholder, replaced in Task 7):

```kotlin
package ch.bigli.passes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import ch.bigli.passes.ui.theme.PassesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PassesTheme { Text("Passes") } }
    }
}
```

- [ ] **Step 6: Generate Gradle wrapper**

Run: `gradle wrapper --gradle-version 8.9` (if `gradle` unavailable, copy a wrapper from any Android Studio project, or run `./gradlew` after Studio generates it).
Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/` created.

- [ ] **Step 7: Verify it builds**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (Requires SDK from Prerequisites.)

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle app/build.gradle.kts app/src/main gradlew gradlew.bat
git commit -m "chore: scaffold Android Compose project"
```

---

## Task 1: Domain model

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/domain/Pass.kt`
- Create: `app/src/main/java/ch/bigli/passes/domain/ImportError.kt`
- Test: `app/src/test/java/ch/bigli/passes/domain/PassTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package ch.bigli.passes.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassTest {
    @Test fun `pass exposes primary field values`() {
        val pass = Pass(
            id = "abc",
            type = PassType.BOARDING,
            title = "ZRH → JFK",
            subtitle = "SWISS",
            organization = "SWISS",
            bgColor = 0xFF1A73E8,
            fgColor = 0xFFFFFFFF,
            fields = listOf(PassField("GATE", "A12", FieldPosition.PRIMARY)),
            barcode = Barcode(BarcodeFormat.QR, "M1SWISS", "M1SWISS"),
            relevantDate = null,
            rawFilePath = "/data/x.pkpass",
            sourceFormat = SourceFormat.PKPASS,
            updateInfo = null,
        )
        assertEquals("A12", pass.fields.first { it.position == FieldPosition.PRIMARY }.value)
        assertTrue(pass.barcode != null)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PassTest*"`
Expected: FAIL — `Pass` unresolved.

- [ ] **Step 3: Write minimal implementation** — Create `domain/Pass.kt`

```kotlin
package ch.bigli.passes.domain

import java.time.Instant

enum class PassType { BOARDING, EVENT, LOYALTY, COUPON, GENERIC }
enum class SourceFormat { PKPASS, GOOGLE_JSON, PDF, MANUAL }
enum class BarcodeFormat { QR, PDF417, AZTEC, CODE128 }
enum class FieldPosition { HEADER, PRIMARY, SECONDARY, AUXILIARY }

data class PassField(val label: String, val value: String, val position: FieldPosition)

data class Barcode(val format: BarcodeFormat, val message: String, val altText: String?)

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
    val rawFilePath: String,
    val sourceFormat: SourceFormat,
    val updateInfo: UpdateInfo?,
)
```

Create `domain/ImportError.kt`:

```kotlin
package ch.bigli.passes.domain

sealed class ImportError(message: String) : Exception(message) {
    class UnsupportedFormat(detail: String) : ImportError("Unsupported format: $detail")
    class CorruptFile(detail: String) : ImportError("Corrupt file: $detail")
    class NoBarcode(detail: String) : ImportError("No barcode found: $detail")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PassTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/domain app/src/test/java/ch/bigli/passes/domain
git commit -m "feat: add Pass domain model and import errors"
```

---

## Task 2: pkpass importer

Parses `.pkpass` (a zip containing `pass.json`). Maps Apple's structure to `Pass`.

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/importing/PassImporter.kt`
- Create: `app/src/main/java/ch/bigli/passes/importing/PkPassJson.kt`
- Create: `app/src/main/java/ch/bigli/passes/importing/PkPassImporter.kt`
- Test: `app/src/test/java/ch/bigli/passes/importing/PkPassImporterTest.kt`
- Fixture: `app/src/test/resources/fixtures/sample.pkpass`, `app/src/test/resources/fixtures/notazip.pkpass`, `app/src/test/resources/fixtures/nopassjson.pkpass`

- [ ] **Step 1: Build fixtures**

Create a valid boarding-pass `.pkpass` fixture. Run this script (adjust path); it writes a minimal but valid pkpass zip:

```bash
mkdir -p app/src/test/resources/fixtures
cd $(mktemp -d)
cat > pass.json <<'JSON'
{
  "formatVersion": 1,
  "passTypeIdentifier": "pass.ch.bigli.test",
  "serialNumber": "ABC123",
  "teamIdentifier": "TEAM",
  "organizationName": "SWISS",
  "description": "Boarding pass ZRH-JFK",
  "backgroundColor": "rgb(26,115,232)",
  "foregroundColor": "rgb(255,255,255)",
  "relevantDate": "2026-08-01T10:45:00Z",
  "barcode": { "format": "PKBarcodeFormatQR", "message": "M1SWISS ZRHJFK", "messageEncoding": "iso-8859-1", "altText": "M1SWISS" },
  "boardingPass": {
    "transitType": "PKTransitTypeAir",
    "headerFields": [ { "key": "boards", "label": "BOARDS", "value": "10:45" } ],
    "primaryFields": [ { "key": "origin", "label": "ZRH", "value": "Zurich" }, { "key": "dest", "label": "JFK", "value": "New York" } ],
    "secondaryFields": [ { "key": "gate", "label": "GATE", "value": "A12" } ],
    "auxiliaryFields": [ { "key": "seat", "label": "SEAT", "value": "14C" } ]
  }
}
JSON
# zip with pass.json at root (icon not required for parsing)
zip -q sample.pkpass pass.json
cp sample.pkpass "$OLDPWD/app/src/test/resources/fixtures/sample.pkpass"
# malformed: not a zip
echo "not a zip" > "$OLDPWD/app/src/test/resources/fixtures/notazip.pkpass"
# malformed: valid zip, no pass.json
echo "hi" > other.txt && zip -q nopassjson.pkpass other.txt
cp nopassjson.pkpass "$OLDPWD/app/src/test/resources/fixtures/nopassjson.pkpass"
cd "$OLDPWD"
```

Verify: `ls -la app/src/test/resources/fixtures/` shows the three files.

- [ ] **Step 2: Write the failing test**

```kotlin
package ch.bigli.passes.importing

import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.PassType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PkPassImporterTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) { "missing $name" }

    private val importer = PkPassImporter()

    @Test fun `parses boarding pass core fields`() {
        val pass = importer.import(fixture("sample.pkpass").readBytes(), "/data/sample.pkpass")
        assertEquals(PassType.BOARDING, pass.type)
        assertEquals("SWISS", pass.organization)
        assertEquals(0xFF1A73E8, pass.bgColor)
        assertEquals(BarcodeFormat.QR, pass.barcode!!.format)
        assertEquals("M1SWISS ZRHJFK", pass.barcode!!.message)
        // primary fields preserved with position
        assertEquals(2, pass.fields.count { it.position == FieldPosition.PRIMARY })
        assertEquals("A12", pass.fields.first { it.label == "GATE" }.value)
    }

    @Test fun `rejects non-zip file`() {
        assertThrows(ImportError.CorruptFile::class.java) {
            importer.import(fixture("notazip.pkpass").readBytes(), "/data/x.pkpass")
        }
    }

    @Test fun `rejects zip without pass json`() {
        assertThrows(ImportError.CorruptFile::class.java) {
            importer.import(fixture("nopassjson.pkpass").readBytes(), "/data/x.pkpass")
        }
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PkPassImporterTest*"`
Expected: FAIL — `PkPassImporter` unresolved.

- [ ] **Step 4: Write the importer interface** — Create `importing/PassImporter.kt`

```kotlin
package ch.bigli.passes.importing

import ch.bigli.passes.domain.Pass

/** Converts raw file bytes of one specific format into a domain [Pass]. */
interface PassImporter {
    /** @param rawFilePath where the raw bytes will be persisted; stored on the Pass. */
    fun import(bytes: ByteArray, rawFilePath: String): Pass
}
```

- [ ] **Step 5: Write the pass.json DTOs** — Create `importing/PkPassJson.kt`

```kotlin
package ch.bigli.passes.importing

import kotlinx.serialization.SerialName
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
)

@Serializable
data class PkField(
    val key: String,
    val label: String? = null,
    val value: String,
)
```

- [ ] **Step 6: Write the importer** — Create `importing/PkPassImporter.kt`

```kotlin
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

    override fun import(bytes: ByteArray, rawFilePath: String): Pass {
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
            && !pj.serialNumber.isNullOrBlank() && !pj.passTypeIdentifier.isNullOrBlank()) {
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
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PkPassImporterTest*"`
Expected: PASS (all 3 tests).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/importing app/src/test/java/ch/bigli/passes/importing app/src/test/resources/fixtures
git commit -m "feat: add pkpass importer mapping pass.json to Pass"
```

---

## Task 3: Barcode renderer

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/barcode/BarcodeRenderer.kt`
- Test: `app/src/test/java/ch/bigli/passes/barcode/BarcodeRendererTest.kt`

Uses `android.graphics.Bitmap`, so the test runs under Robolectric.

- [ ] **Step 1: Write the failing test**

```kotlin
package ch.bigli.passes.barcode

import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BarcodeRendererTest {
    private val renderer = BarcodeRenderer()

    @Test fun `renders qr to square bitmap of requested size`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.QR, "HELLO", null), 300, 300)
        assertNotNull(bmp)
        assertEquals(300, bmp.width)
        assertEquals(300, bmp.height)
    }

    @Test fun `renders code128 as wide bitmap`() {
        val bmp = renderer.render(Barcode(BarcodeFormat.CODE128, "12345678", null), 600, 200)
        assertEquals(600, bmp.width)
        assertEquals(200, bmp.height)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*BarcodeRendererTest*"`
Expected: FAIL — `BarcodeRenderer` unresolved.

- [ ] **Step 3: Write minimal implementation** — Create `barcode/BarcodeRenderer.kt`

```kotlin
package ch.bigli.passes.barcode

import android.graphics.Bitmap
import android.graphics.Color
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import com.google.zxing.BarcodeFormat as ZxFormat
import com.google.zxing.MultiFormatWriter

class BarcodeRenderer {
    fun render(barcode: Barcode, width: Int, height: Int): Bitmap {
        val zxFormat = when (barcode.format) {
            BarcodeFormat.QR -> ZxFormat.QR_CODE
            BarcodeFormat.PDF417 -> ZxFormat.PDF_417
            BarcodeFormat.AZTEC -> ZxFormat.AZTEC
            BarcodeFormat.CODE128 -> ZxFormat.CODE_128
        }
        val matrix = MultiFormatWriter().encode(barcode.message, zxFormat, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*BarcodeRendererTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/barcode app/src/test/java/ch/bigli/passes/barcode
git commit -m "feat: render barcodes to bitmaps via ZXing"
```

---

## Task 4: Room persistence

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/data/PassEntity.kt`
- Create: `app/src/main/java/ch/bigli/passes/data/PassDao.kt`
- Create: `app/src/main/java/ch/bigli/passes/data/PassDatabase.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassDaoTest.kt`

Fields/barcode are stored as JSON strings (small, avoids extra tables for Phase 1).

- [ ] **Step 1: Write the failing test**

```kotlin
package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.Barcode
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.domain.PassField
import ch.bigli.passes.domain.PassType
import ch.bigli.passes.domain.SourceFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassDaoTest {
    private lateinit var db: PassDatabase
    private lateinit var dao: PassDao

    private fun sample(id: String) = Pass(
        id = id, type = PassType.EVENT, title = "Concert", subtitle = "Venue",
        organization = "Org", bgColor = 0xFF34A853, fgColor = 0xFFFFFFFF,
        fields = listOf(PassField("WHEN", "21:00", FieldPosition.PRIMARY)),
        barcode = Barcode(BarcodeFormat.QR, "XYZ", "XYZ"),
        relevantDate = null, rawFilePath = "/data/$id.pkpass",
        sourceFormat = SourceFormat.PKPASS, updateInfo = null,
    )

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        dao = db.passDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `insert then observe returns the pass`() = runTest {
        dao.insert(sample("a").toEntity())
        val all = dao.observeAll().first()
        assertEquals(1, all.size)
        val roundTripped = all.first().toDomain()
        assertEquals("Concert", roundTripped.title)
        assertEquals(BarcodeFormat.QR, roundTripped.barcode!!.format)
        assertEquals("21:00", roundTripped.fields.first().value)
    }

    @Test fun `getById then delete removes it`() = runTest {
        dao.insert(sample("b").toEntity())
        assertEquals("Concert", dao.getById("b")!!.toDomain().title)
        dao.deleteById("b")
        assertNull(dao.getById("b"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PassDaoTest*"`
Expected: FAIL — `PassDatabase` unresolved.

- [ ] **Step 3: Write the entity + mappers** — Create `data/PassEntity.kt`

```kotlin
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
    val title: String,
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
)

fun Pass.toEntity() = PassEntity(
    id = id,
    type = type.name,
    title = title,
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
)

fun PassEntity.toDomain() = Pass(
    id = id,
    type = PassType.valueOf(type),
    title = title,
    subtitle = subtitle,
    organization = organization,
    bgColor = bgColor,
    fgColor = fgColor,
    fields = json.decodeFromString(ListSerializer(PassField.serializer()), fieldsJson),
    barcode = barcodeJson?.let { json.decodeFromString(Barcode.serializer(), it) },
    relevantDate = relevantDateEpoch?.let { java.time.Instant.ofEpochMilli(it) },
    rawFilePath = rawFilePath,
    sourceFormat = SourceFormat.valueOf(sourceFormat),
    updateInfo = updateInfoJson?.let { json.decodeFromString(UpdateInfo.serializer(), it) },
)
```

NOTE: This requires the domain classes `PassField`, `Barcode`, `UpdateInfo` to be `@Serializable`. Update `domain/Pass.kt` now: add `import kotlinx.serialization.Serializable` and annotate `PassField`, `Barcode`, and `UpdateInfo` with `@Serializable`. (The enums are serialized by name automatically.)

- [ ] **Step 4: Write the DAO** — Create `data/PassDao.kt`

```kotlin
package ch.bigli.passes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PassDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PassEntity)

    @Query("SELECT * FROM passes ORDER BY relevantDateEpoch IS NULL, relevantDateEpoch ASC, title ASC")
    fun observeAll(): Flow<List<PassEntity>>

    @Query("SELECT * FROM passes WHERE id = :id")
    suspend fun getById(id: String): PassEntity?

    @Query("DELETE FROM passes WHERE id = :id")
    suspend fun deleteById(id: String)
}
```

- [ ] **Step 5: Write the database** — Create `data/PassDatabase.kt`

```kotlin
package ch.bigli.passes.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PassEntity::class], version = 1, exportSchema = false)
abstract class PassDatabase : RoomDatabase() {
    abstract fun passDao(): PassDao
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PassDaoTest*"`
Expected: PASS (both tests).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/data app/src/main/java/ch/bigli/passes/domain app/src/test/java/ch/bigli/passes/data
git commit -m "feat: add Room persistence for passes"
```

---

## Task 5: Repository (import + storage orchestration)

Wires importer + file copy + DAO. Sniffs format (Phase 1: pkpass only, else `UnsupportedFormat`).

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/data/PassRepository.kt`
- Test: `app/src/test/java/ch/bigli/passes/data/PassRepositoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package ch.bigli.passes.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PassRepositoryTest {
    private lateinit var db: PassDatabase
    private lateinit var ctx: Context
    private lateinit var repo: PassRepository

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
    }

    @After fun tearDown() = db.close()

    @Test fun `importing a pkpass persists it and copies the raw file`() = runTest {
        val pass = repo.import(fixture("sample.pkpass"), "sample.pkpass")
        assertEquals("SWISS", pass.organization)
        assertTrue(File(pass.rawFilePath).exists())
        assertEquals(1, repo.observeAll().first().size)
    }

    @Test fun `importing an unknown format throws UnsupportedFormat`() = runTest {
        assertThrows(ImportError.UnsupportedFormat::class.java) {
            repo.import("hello world".toByteArray(), "note.txt")
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PassRepositoryTest*"`
Expected: FAIL — `PassRepository` unresolved.

- [ ] **Step 3: Write minimal implementation** — Create `data/PassRepository.kt`

```kotlin
package ch.bigli.passes.data

import android.content.Context
import ch.bigli.passes.domain.ImportError
import ch.bigli.passes.domain.Pass
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class PassRepository(
    private val context: Context,
    private val dao: PassDao,
    private val pkPassImporter: PkPassImporter,
) {
    fun observeAll(): Flow<List<Pass>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: String): Pass? = withContext(Dispatchers.IO) { dao.getById(id)?.toDomain() }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        dao.getById(id)?.let { runCatching { File(it.rawFilePath).delete() } }
        dao.deleteById(id)
    }

    /** @param displayName the original file name, used only for format sniffing. */
    suspend fun import(bytes: ByteArray, displayName: String): Pass = withContext(Dispatchers.IO) {
        if (!isPkPass(bytes, displayName)) {
            throw ImportError.UnsupportedFormat(displayName)
        }
        val dir = File(context.filesDir, "passes").apply { mkdirs() }
        val target = File(dir, "${UUID.randomUUID()}.pkpass")
        target.writeBytes(bytes)
        val pass = try {
            pkPassImporter.import(bytes, target.absolutePath)
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
        dao.insert(pass.toEntity())
        pass
    }

    private fun isPkPass(bytes: ByteArray, name: String): Boolean {
        val zipMagic = bytes.size >= 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
        return zipMagic && (name.endsWith(".pkpass", true) || name.endsWith(".zip", true) || true)
    }
}
```

NOTE on `isPkPass`: Phase 1 accepts any zip as a pkpass candidate (the importer rejects it with `CorruptFile` if `pass.json` is absent). Non-zip bytes → `UnsupportedFormat`. This keeps sniffing honest without over-engineering; later phases add real per-format detection.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PassRepositoryTest*"`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/data/PassRepository.kt app/src/test/java/ch/bigli/passes/data/PassRepositoryTest.kt
git commit -m "feat: add PassRepository orchestrating import and storage"
```

---

## Task 6: ViewModels

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/ui/PassListViewModel.kt`
- Create: `app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt`
- Test: `app/src/test/java/ch/bigli/passes/ui/PassListViewModelTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package ch.bigli.passes.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.bigli.passes.data.PassDatabase
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.importing.PkPassImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PassListViewModelTest {
    private lateinit var repo: PassRepository
    private lateinit var db: PassDatabase

    private fun fixture(name: String) =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")).readBytes()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PassDatabase::class.java).allowMainThreadQueries().build()
        repo = PassRepository(ctx, db.passDao(), PkPassImporter())
    }

    @After fun tearDown() { db.close(); Dispatchers.resetMain() }

    @Test fun `state reflects imported passes`() = runTest {
        val vm = PassListViewModel(repo)
        repo.import(fixture("sample.pkpass"), "sample.pkpass")
        val passes = vm.passes.first { it.isNotEmpty() }
        assertEquals(1, passes.size)
        assertEquals("SWISS", passes.first().organization)
    }

    @Test fun `import error is surfaced on the error flow`() = runTest {
        val vm = PassListViewModel(repo)
        vm.importBytes("bad".toByteArray(), "note.txt")
        val err = vm.errors.first()
        assertTrue(err.contains("Unsupported", ignoreCase = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*PassListViewModelTest*"`
Expected: FAIL — `PassListViewModel` unresolved.

- [ ] **Step 3: Write the list ViewModel** — Create `ui/PassListViewModel.kt`

```kotlin
package ch.bigli.passes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.domain.Pass
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PassListViewModel(private val repo: PassRepository) : ViewModel() {
    val passes: StateFlow<List<Pass>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors

    fun importBytes(bytes: ByteArray, displayName: String) {
        viewModelScope.launch {
            try {
                repo.import(bytes, displayName)
            } catch (e: Exception) {
                _errors.emit(e.message ?: "Import failed")
            }
        }
    }
}
```

- [ ] **Step 4: Write the detail ViewModel** — Create `ui/PassDetailViewModel.kt`

```kotlin
package ch.bigli.passes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.domain.Pass
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PassDetailViewModel(private val repo: PassRepository, private val passId: String) : ViewModel() {
    private val _pass = MutableStateFlow<Pass?>(null)
    val pass: StateFlow<Pass?> = _pass

    init {
        viewModelScope.launch { _pass.value = repo.getById(passId) }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch { repo.delete(passId); onDone() }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "*PassListViewModelTest*"`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/ch/bigli/passes/ui/PassListViewModel.kt app/src/main/java/ch/bigli/passes/ui/PassDetailViewModel.kt app/src/test/java/ch/bigli/passes/ui/PassListViewModelTest.kt
git commit -m "feat: add list and detail ViewModels"
```

---

## Task 7: Compose UI + navigation + import launcher

No unit test (UI wiring); verified manually in Task 8. Uses simple manual DI via the `Application`.

**Files:**
- Create: `app/src/main/java/ch/bigli/passes/PassApp.kt`
- Create: `app/src/main/java/ch/bigli/passes/ui/PassListScreen.kt`
- Create: `app/src/main/java/ch/bigli/passes/ui/PassDetailScreen.kt`
- Modify: `app/src/main/java/ch/bigli/passes/MainActivity.kt` (replace placeholder)
- Modify: `app/src/main/AndroidManifest.xml` (register Application)

- [ ] **Step 1: Application-scoped DI container** — Create `PassApp.kt`

```kotlin
package ch.bigli.passes

import android.app.Application
import androidx.room.Room
import ch.bigli.passes.data.PassDatabase
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.importing.PkPassImporter

class PassApp : Application() {
    lateinit var repository: PassRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val db = Room.databaseBuilder(this, PassDatabase::class.java, "passes.db").build()
        repository = PassRepository(this, db.passDao(), PkPassImporter())
    }
}
```

- [ ] **Step 2: Register Application in manifest**

In `app/src/main/AndroidManifest.xml`, add `android:name=".PassApp"` to the `<application>` tag:

```xml
    <application
        android:name=".PassApp"
        android:allowBackup="true"
        android:label="Passes"
        android:theme="@android:style/Theme.Material.NoActionBar">
```

- [ ] **Step 3: List screen** — Create `ui/PassListScreen.kt`

```kotlin
package ch.bigli.passes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassListScreen(
    viewModel: PassListViewModel,
    onImportClick: () -> Unit,
    onPassClick: (String) -> Unit,
) {
    val passes by viewModel.passes.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.errors.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Passes") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = onImportClick) {
                Icon(Icons.Filled.Add, contentDescription = "Import pass")
            }
        },
    ) { padding ->
        if (passes.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center) {
                Text(
                    "No passes yet. Tap + to import a .pkpass file.",
                    Modifier.fillMaxWidth().padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(padding),
            ) {
                items(passes, key = { it.id }) { pass ->
                    PassCard(pass) { onPassClick(pass.id) }
                }
            }
        }
    }
}

@Composable
private fun PassCard(pass: Pass, onClick: () -> Unit) {
    val bg = pass.bgColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    val fg = pass.fgColor?.let { Color(it) } ?: Color.White
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Text(
            (pass.subtitle ?: pass.organization ?: pass.type.name).uppercase(),
            color = fg.copy(alpha = 0.85f), fontSize = 11.sp,
        )
        Text(pass.title, color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        val summary = pass.fields.filter { it.position != FieldPosition.PRIMARY }
            .take(3).joinToString("   ") { "${it.label}: ${it.value}" }
        if (summary.isNotEmpty()) {
            Text(summary, color = fg.copy(alpha = 0.9f), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
```

- [ ] **Step 4: Detail screen with brightness boost** — Create `ui/PassDetailScreen.kt`

```kotlin
package ch.bigli.passes.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.bigli.passes.barcode.BarcodeRenderer
import ch.bigli.passes.domain.BarcodeFormat
import ch.bigli.passes.domain.FieldPosition
import ch.bigli.passes.domain.Pass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassDetailScreen(
    viewModel: PassDetailViewModel,
    onBack: () -> Unit,
) {
    val pass by viewModel.pass.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Boost brightness while this screen is visible; restore on exit.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val original = window?.attributes?.screenBrightness
        window?.attributes = window?.attributes?.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = original ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    val p = pass
    val bg = p?.bgColor?.let { Color(it) } ?: Color(0xFF1A73E8)
    val fg = p?.fgColor?.let { Color(it) } ?: Color.White

    Scaffold(
        containerColor = bg,
        topBar = {
            TopAppBar(
                title = { Text(p?.organization ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = fg)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.delete(onBack) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = fg)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = fg),
            )
        },
    ) { padding ->
        if (p == null) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(p.title, color = fg, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                p.fields.filter { it.position != FieldPosition.PRIMARY }.take(4).forEach { f ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(f.label, color = fg.copy(alpha = 0.7f), fontSize = 10.sp)
                        Text(f.value, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            p.barcode?.let { bc ->
                val renderer = remember { BarcodeRenderer() }
                val square = bc.format == BarcodeFormat.QR || bc.format == BarcodeFormat.AZTEC
                val bmp = remember(bc) {
                    if (square) renderer.render(bc, 600, 600) else renderer.render(bc, 800, 300)
                }
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(bmp.asImageBitmap(), contentDescription = "Barcode", modifier = Modifier.size(240.dp))
                    bc.altText?.let {
                        Text(it, color = Color.Black, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Text("☀ Screen brightened for scanning", color = fg.copy(alpha = 0.8f),
                    fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            } ?: Text("No barcode on this pass", color = fg)
            Spacer(Modifier.weight(1f))
        }
    }
}
```

- [ ] **Step 5: Wire MainActivity (nav + file picker)** — Replace `MainActivity.kt`

```kotlin
package ch.bigli.passes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import ch.bigli.passes.data.PassRepository
import ch.bigli.passes.ui.PassDetailScreen
import ch.bigli.passes.ui.PassDetailViewModel
import ch.bigli.passes.ui.PassListScreen
import ch.bigli.passes.ui.PassListViewModel
import ch.bigli.passes.ui.theme.PassesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as PassApp).repository
        setContent { PassesTheme { AppNav(repo) } }
    }
}

private class VmFactory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

@Composable
private fun AppNav(repo: PassRepository) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "list") {
        composable("list") {
            val vm: PassListViewModel = viewModel(factory = VmFactory { PassListViewModel(repo) })
            val context = androidx.compose.ui.platform.LocalContext.current
            val picker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val name = uri.lastPathSegment ?: "pass.pkpass"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) vm.importBytes(bytes, name)
                }
            }
            PassListScreen(
                viewModel = vm,
                onImportClick = { picker.launch(arrayOf("*/*")) },
                onPassClick = { id -> nav.navigate("detail/$id") },
            )
        }
        composable(
            "detail/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments!!.getString("id")!!
            val vm: PassDetailViewModel = viewModel(factory = VmFactory { PassDetailViewModel(repo, id) })
            PassDetailScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
    }
}
```

NOTE: `OpenDocument` with `arrayOf("*/*")` is used because many providers don't advertise the `application/vnd.apple.pkpass` MIME type reliably. Format sniffing in the repository handles correctness.

- [ ] **Step 6: Add material icons dependency**

The screens use `androidx.compose.material:material-icons-extended` is NOT needed — `Icons.Filled.Add`, `Delete`, and `Icons.AutoMirrored.Filled.ArrowBack` are in the core `material-icons-core` bundled with `material3`. Verify no missing-icon errors at build; if any icon is unresolved, add to `app/build.gradle.kts` dependencies:
`implementation("androidx.compose.material:material-icons-extended")`

- [ ] **Step 7: Build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main
git commit -m "feat: add Compose UI, navigation, and file-picker import"
```

---

## Task 8: End-to-end verification

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all tests pass.

- [ ] **Step 2: Install on device/emulator**

Run: `./gradlew :app:installDebug` (device or emulator connected via `adb devices`).
Expected: app installs as "Passes".

- [ ] **Step 3: Manual smoke test**

- Push the fixture to the device: `adb push app/src/test/resources/fixtures/sample.pkpass /sdcard/Download/sample.pkpass`
- Open the app → tap **+** → pick `sample.pkpass` from Downloads.
- Verify a blue "ZRH → JFK" card appears with SWISS + gate/seat summary.
- Tap it → detail screen shows the QR code on a white card, screen visibly brightens, alt text "M1SWISS" shows.
- Tap back, tap the card again, tap the trash icon → returns to list, card gone.

- [ ] **Step 4: Commit any fixes made during verification**

```bash
git add -A
git commit -m "fix: address issues found during end-to-end verification"
```

---

## Self-Review notes

- **Spec coverage:** pkpass import (Task 2), common Pass model (Task 1), Room + raw-file storage (Tasks 4–5), ZXing barcode (Task 3), list + detail UI (Task 7), max-brightness (Task 7 detail), file-picker import (Task 7), typed error handling incl. NoBarcode/CorruptFile/UnsupportedFormat (Tasks 1,2,5,6). Signature verification, auto-update, PDF/Google/manual importers are explicitly deferred to Phases 2–4 per the spec.
- **Deferred from this plan:** "Open with"/share intents (Phase 2) — Phase 1 uses the in-app file picker only.
- **Type consistency:** `Pass`/`Barcode`/`PassField`/`UpdateInfo` signatures are defined once in Task 1 and made `@Serializable` in Task 4; entity mappers, importer, renderer, and ViewModels all reference those exact shapes.
