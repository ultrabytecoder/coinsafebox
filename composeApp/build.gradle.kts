import java.util.Properties
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

// Desktop network "flavor": baked into the jar at build time so a single build
// yields an unambiguous testnet OR mainnet artifact (mirrors the Android
// productionTestnet/productionMainnet product flavors). Override with
// ./gradlew -PkkNetwork=mainnet (default: testnet).
val kkNetwork = (findProperty("kkNetwork") as? String ?: "testnet").lowercase()
require(kkNetwork in listOf("testnet", "mainnet")) {
    "kkNetwork must be 'testnet' or 'mainnet' (got '$kkNetwork')"
}
val kkIsTestnet = kkNetwork == "testnet"
val kkEtherscanKey = localProperties.getProperty(
    if (kkIsTestnet) "etherscan.testnet.api.key" else "etherscan.mainnet.api.key", ""
)
val kkWcProjectId = localProperties.getProperty("wc.project.id", "")
// Write the generated constant eagerly at configuration time so the srcDir below
// is always valid before compilation. Content is deterministic per (network, key),
// so Gradle's input-aware up-to-date check still skips recompiles when unchanged.
val kkGenDir = layout.buildDirectory.dir("generated/kknetwork/$kkNetwork").get().asFile
kkGenDir.mkdirs()
File(kkGenDir, "DesktopBuildConfig.kt").writeText(
    """
    package com.ultrabytecoder.coinsafebox

    object DesktopBuildConfig {
        const val IS_TESTNET = ${kkIsTestnet}
        const val ETHERSCAN_API_KEY = "${kkEtherscanKey.replace("\\", "\\\\").replace("\"", "\\\"")}"
        const val WC_PROJECT_ID = "${kkWcProjectId.replace("\\", "\\\\").replace("\"", "\\\"")}"
    }
    """.trimIndent()
)

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilerOptions {
            // Aligned with the desktop (jvm) target so the shared jvmMain source
            // set compiles to a single, consistent bytecode level for both.
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xexpect-actual-classes")
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
        iosTarget.compilations["main"].cinterops {
            create("CommonCrypto") {
                defFile = File(projectDir, "src/iosMain/cinterop/CommonCrypto.def")
            }
            create("libsodium") {
                defFile = File(projectDir, "src/iosMain/cinterop/libsodium.def")
                // Per-target static lib location (built by scripts/build-libsodium-ios.sh).
                val archDir = when (iosTarget.name) {
                    "iosArm64" -> "ios-arm64"
                    "iosSimulatorArm64" -> "ios-sim-arm64"
                    else -> error("unexpected iOS target ${iosTarget.name}")
                }
                val libDir = File(projectDir, "nativeLibs/libsodium/$archDir/lib")
                val incDir = File(projectDir, "nativeLibs/libsodium/$archDir/include")
                includeDirs(incDir)
                extraOpts("-libraryPath", libDir.absolutePath)
            }
        }
    }

    sourceSets {
        val currentOs = org.gradle.internal.os.OperatingSystem.current()

        // Shared JVM source set: actuals used by both the desktop (jvm) target
        // and the Android target (app + local unit tests). Android local unit
        // tests run on a plain JVM without the AndroidKeyStore provider, so the
        // shared HardwareKeyStore actual must degrade gracefully there.
        val jvmMain by creating { dependsOn(commonMain.get()) }
        val jvmTest by creating { dependsOn(commonTest.get()) }

        // desktopMain/desktopTest are wired below in their existing `by getting`
        // dependency blocks (a second `by getting` for the same source set is a
        // "Conflicting declarations" error). androidMain/androidUnitTest have no
        // prior declaration, so they use `named(...)`.
        named("androidMain") { dependsOn(jvmMain) }
        named("androidUnitTest") { dependsOn(jvmTest) }

        // QR generation on the shared JVM source set (desktop + Android) uses ZXing
        // (Java 8 bytecode). The goquati:qr JVM artifact targets class 65 (Java 21),
        // which a Java 17 runtime cannot load, so it is confined to iosMain instead.
        jvmMain.dependencies {
            implementation(libs.zxing.core)
        }

        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
            implementation(libs.androidx.security.crypto)
            implementation(libs.koin.android)
            implementation(libs.ktor.client.okhttp)
            implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.23.0")
            implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            implementation("com.journeyapps:zxing-android-embedded:4.3.0")
            implementation("androidx.camera:camera-core:1.6.1")
            implementation("androidx.camera:camera-camera2:1.6.1")
            implementation("androidx.camera:camera-lifecycle:1.6.1")
            implementation("androidx.camera:camera-view:1.6.1")
        }
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)

            // Ktor
            implementation(libs.ktor.client.core)

            // Koin
            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            // Navigation
            implementation(libs.navigation.compose)
            // Bitcoin
            implementation(libs.bitcoin.kmp)
            // BigNum for multiplatform BigDecimal support
            implementation("com.ionspin.kotlin:bignum:0.3.10")
            // Ed25519 cryptography (pure Kotlin)
            implementation(libs.curve25519.kotlin)
            // Multiplatform Icons (replacement for androidx.compose.material.icons)
            implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

            // Date/time and human-readable formatting
            implementation(libs.kotlinx.datetime)
            implementation(libs.human.readable)

            // SQLDelight
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines.extensions)

            implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
            implementation("dev.whyoleg.cryptography:cryptography-provider-optimal:0.6.0")

            // Cryptographically secure random bytes
            implementation("org.kotlincrypto.random:crypto-rand:0.6.0")
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            implementation(libs.yet300.sqlcipher.driver)
            implementation(libs.ktor.client.darwin)
            // QR generation on iOS uses goquati:qr (native; the JVM artifact is
            // avoided because it targets class 65 and breaks a Java 17 runtime).
            implementation(libs.qr)
        }

        val desktopJniTarget = when {
            currentOs.isLinux -> "linux"
            currentOs.isMacOsX -> "darwin"
            currentOs.isWindows -> "mingw"
            else -> error("Unsupported OS for desktop target")
        }
        val desktopMain by getting {
            dependsOn(jvmMain)
            // Baked network constant (DesktopBuildConfig) is desktop-only; the
            // Android target resolves its network via BuildConfig.IS_TESTNET instead.
            kotlin.srcDir(kkGenDir)
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.jna)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.ktor.client.okhttp)
                implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm-$desktopJniTarget:0.23.0")
                implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.coroutines.test)
        }
        val desktopTest by getting {
            dependsOn(jvmTest)
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.junit)
                implementation(libs.sqldelight.sqlite.driver)
            }
        }

        androidUnitTest.dependencies {
            val target = when {
                currentOs.isLinux -> "linux"
                currentOs.isMacOsX -> "darwin"
                currentOs.isWindows -> "mingw"
                else -> error("Unsupported OS $currentOs")
            }
            implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm-$target:0.23.0")
        }
        iosArm64Test.dependencies {
            implementation("fr.acinq.secp256k1:secp256k1-kmp-iosarm64:0.23.0")
        }

        iosX64Test.dependencies {
            implementation("fr.acinq.secp256k1:secp256k1-kmp-iosx64:0.23.0")
        }

        iosSimulatorArm64Test.dependencies {
            implementation("fr.acinq.secp256k1:secp256k1-kmp-iossimulatorarm64:0.23.0")
        }
    }
}

android {
    namespace = "com.ultrabytecoder.coinsafebox"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.ultrabytecoder.coinsafebox"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 4
        versionName = "1.2.0"
        buildConfigField("String", "WC_PROJECT_ID", "\"${localProperties.getProperty("wc.project.id", "")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "dimension"

    productFlavors {
        create("productionTestnet") {
            dimension = "dimension"
            applicationIdSuffix = ".testnet"
            versionNameSuffix = "-prod-testnet"
            buildConfigField("boolean", "IS_TESTNET", "true")
            buildConfigField("String", "ETHERSCAN_API_KEY", "\"${localProperties.getProperty("etherscan.testnet.api.key", "")}\"")
        }
        create("productionMainnet") {
            dimension = "dimension"
            applicationIdSuffix = ".mainnet"
            versionNameSuffix = "-prod-mainnet"
            buildConfigField("boolean", "IS_TESTNET", "false")
            buildConfigField("String", "ETHERSCAN_API_KEY", "\"${localProperties.getProperty("etherscan.mainnet.api.key", "")}\"")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        // Aligned with the Kotlin jvmTarget (JVM_17) for the shared jvmMain source set.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    debugImplementation(libs.compose.uiTooling)
}

sqldelight {
    databases {
        create("CoinSafeBoxDatabase") {
            packageName.set("com.ultrabytecoder.coinsafebox.db")
        }
    }
    // SQLCipher (yet300 driver) provides its own sqlite3_* symbols on Apple targets —
    // do not link the system SQLite or the encrypted binary would be mislinked.
    linkSqlite.set(false)
}

// The Compose `packageUberJarForCurrentOS` uber jar bundles signed third-party
// dependencies (e.g. BouncyCastle) whose META-INF signature files make the JVM
// throw "Invalid signature file digest for Manifest main attributes" once the jar
// is repackaged. Strip those signature entries from the final jar so `java -jar`
// works out of the box (all entries in this jar are deflated, so rewriting is safe).
abstract class StripUberJarSignaturesTask : DefaultTask() {
    @get:Internal
    abstract val jarsDir: DirectoryProperty

    @TaskAction
    fun run() {
        val sigExtensions = listOf("SF", "DSA", "RSA", "EC")
        val dir = jarsDir.get().asFile
        for (jar in dir.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList()) {
            val zip = ZipFile(jar)
            val removed = mutableListOf<String>()
            val tmp = File(jar.parentFile, "${jar.name}.unsigned")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val isSig = !entry.isDirectory && entry.name.startsWith("META-INF/") &&
                        sigExtensions.any { entry.name.endsWith(".$it") }
                    if (isSig) {
                        removed.add(entry.name)
                        continue
                    }
                    val newEntry = ZipEntry(entry.name)
                    newEntry.time = entry.time
                    out.putNextEntry(newEntry)
                    if (!entry.isDirectory) zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
            }
            zip.close()
            if (removed.isNotEmpty()) {
                tmp.copyTo(jar, overwrite = true)
                tmp.delete()
                logger.lifecycle("stripUberJarSignatures: removed $removed from ${jar.name}")
            } else {
                tmp.delete()
            }
        }
    }
}

val stripUberJarSignatures = tasks.register<StripUberJarSignaturesTask>("stripUberJarSignatures") {
    group = "coinsafebox"
    description = "Removes signature files from signed deps in the packaged desktop uber jar."
    jarsDir.set(project.layout.buildDirectory.dir("compose/jars"))
}

// `packageUberJarForCurrentOS` is created by the compose plugin when the
// `compose.desktop` block below is evaluated, so wire it lazily (configureEach)
// rather than with tasks.named, which would run before the task exists.
tasks.matching { it.name == "packageUberJarForCurrentOS" }
    .configureEach { finalizedBy(stripUberJarSignatures) }

compose.desktop {
    application {
        mainClass = "com.ultrabytecoder.coinsafebox.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Dmg)
            // Suffix per network so testnet and mainnet installers coexist.
            packageName = "CoinSafeBox-$kkNetwork"
            packageVersion = "1.1.1"
            windows {
                menuGroup = "CoinSafeBox"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Distinct per network so the testnet + mainnet installers can coexist
                // on the same machine (the MSI ProductCode/upgrade code). Mirrors the
                // .testnet/.mainnet applicationIdSuffix on Android and the
                // network-suffixed bundle identifiers on iOS.
                upgradeUuid = if (kkIsTestnet) {
                    "7a5d1f8e-3b2c-4d6a-9e1f-0a2b3c4d5e6f"
                } else {
                    "7a5d1f8e-3b2c-4d6b-9e1f-0a2b3c4d5e6f"
                }
            }
        }
    }
}

