import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

class ForgeProductionSigningSettings(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun signingCertificateFingerprint(file: File, storeType: String, password: String, alias: String): String {
    val keyStore = KeyStore.getInstance(storeType)
    val characters = password.toCharArray()
    try {
        file.inputStream().use { keyStore.load(it, characters) }
    } finally {
        characters.fill('\u0000')
    }
    check(keyStore.isKeyEntry(alias)) { "A signing private-key entry is required." }
    val certificate = requireNotNull(keyStore.getCertificate(alias)) { "A signing certificate is required." }
    return MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
}

val allowUnsignedRelease = providers.gradleProperty("forge.allowUnsignedRelease").orNull.let { value ->
    require(value == null || value == "true" || value == "false") {
        "forge.allowUnsignedRelease must be true or false."
    }
    value == "true"
}
val signingPropertiesPath = providers.environmentVariable("FORGE_RELEASE_SIGNING_PROPERTIES").orNull
val signingEnvironment = mapOf(
    "storeFile" to "FORGE_RELEASE_STORE_FILE",
    "storePassword" to "FORGE_RELEASE_STORE_PASSWORD",
    "keyAlias" to "FORGE_RELEASE_KEY_ALIAS",
    "keyPassword" to "FORGE_RELEASE_KEY_PASSWORD",
).mapValues { (_, name) -> providers.environmentVariable(name).orNull }
var releaseSigningProblem: String? = null
val productionSigning = run {
    fun invalid(message: String): ForgeProductionSigningSettings? {
        releaseSigningProblem = message
        return null
    }
    val propertiesPath = signingPropertiesPath
    val hasEnvironment = signingEnvironment.values.any { it != null }
    if (propertiesPath == null && !hasEnvironment) return@run null
    if (allowUnsignedRelease) return@run invalid(
        "Unsigned release verification must run without production signing inputs.",
    )
    if (propertiesPath != null && hasEnvironment) return@run invalid(
        "Use either FORGE_RELEASE_SIGNING_PROPERTIES or the four signing environment variables, not both.",
    )
    try {
        val repository = rootProject.projectDir.canonicalFile.toPath()
        val base: File
        val values: Map<String, String?>
        if (propertiesPath != null) {
            if (propertiesPath.isBlank()) return@run invalid("The external signing properties path is empty.")
            val propertiesFile = rootProject.file(propertiesPath).canonicalFile
            if (propertiesFile.toPath().startsWith(repository)) return@run invalid(
                "Production signing properties must be stored outside the repository.",
            )
            if (!propertiesFile.isFile) return@run invalid("The external signing properties file is unavailable.")
            val properties = Properties().apply { propertiesFile.inputStream().use(::load) }
            values = signingEnvironment.keys.associateWith(properties::getProperty)
            base = propertiesFile.parentFile
        } else {
            values = signingEnvironment
            base = rootProject.projectDir
        }
        if (values.values.any { it.isNullOrBlank() }) return@run invalid(
            "Production signing requires storeFile, storePassword, keyAlias and keyPassword. Partial settings are rejected.",
        )
        val suppliedStore = File(values.getValue("storeFile")!!.trim())
        val store = (if (suppliedStore.isAbsolute) suppliedStore else File(base, suppliedStore.path)).canonicalFile
        if (store.toPath().startsWith(repository)) return@run invalid(
            "The production keystore must be stored outside the repository.",
        )
        if (!store.isFile || store.length() == 0L) return@run invalid("The production keystore is unavailable.")
        ForgeProductionSigningSettings(
            store, values.getValue("storePassword")!!,
            values.getValue("keyAlias")!!.trim(), values.getValue("keyPassword")!!,
        )
    } catch (_: Exception) {
        invalid("Unable to load private production signing settings. Check the external file and paths.")
    }
}

android {
    namespace = "com.forge.workout"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.forge.workout"
        minSdk = 26
        targetSdk = 37
        versionCode = 12
        versionName = "1.4.3"
        testInstrumentationRunner = "com.forge.workout.ForgeTestRunner"
    }
    val validProductionSigning = productionSigning?.takeIf { signing ->
        if (signing.keyAlias.equals("androiddebugkey", ignoreCase = true)) {
            releaseSigningProblem = "The Android debug alias cannot sign production releases."
            false
        } else try {
            val productionFingerprint = signingCertificateFingerprint(
                signing.storeFile, "PKCS12", signing.storePassword, signing.keyAlias,
            )
            val debug = signingConfigs.getByName("debug")
            val debugFingerprint = debug.storeFile?.takeIf(File::isFile)?.let { file ->
                signingCertificateFingerprint(
                    file, debug.storeType ?: KeyStore.getDefaultType(),
                    requireNotNull(debug.storePassword), requireNotNull(debug.keyAlias),
                )
            }
            val legacyDevelopmentFingerprint = "ebf2faa1b45b6a6eea4742e7c31f8144415f5748067af1b9e4159a988ce32adc"
            if (productionFingerprint == legacyDevelopmentFingerprint || productionFingerprint == debugFingerprint) {
                releaseSigningProblem = "A development/debug signing identity cannot sign production releases."
                false
            } else true
        } catch (_: Exception) {
            releaseSigningProblem = "Unable to verify the private production signing identity. Check the external settings."
            false
        }
    }
    validProductionSigning?.let { signing ->
        signingConfigs.create("productionRelease") {
            storeFile = signing.storeFile
            storePassword = signing.storePassword
            keyAlias = signing.keyAlias
            keyPassword = signing.keyPassword
            storeType = "PKCS12"
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("productionRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

val verifyForgeReleaseSigning = tasks.register("verifyForgeReleaseSigning") {
    group = "verification"
    description = "Requires private production signing, or an explicit unsigned verification build."
    doLast {
        releaseSigningProblem?.let { throw GradleException(it) }
        if (productionSigning == null && !allowUnsignedRelease) {
            throw GradleException(
                "Production release signing is not configured. Supply FORGE_RELEASE_SIGNING_PROPERTIES " +
                    "or all four FORGE_RELEASE_* signing variables. CI may explicitly use " +
                    "-Pforge.allowUnsignedRelease=true for an unsigned verification build.",
            )
        }
    }
}
tasks.configureEach {
    if (name.contains("Release") && name != "verifyForgeReleaseSigning") dependsOn(verifyForgeReleaseSigning)
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("androidx.test:runner:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Compose's older transitive Espresso reflects a removed API on Android 17.
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "1024m"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    systemProperty("user.home", rootProject.file(".gradle/robolectric-home").absolutePath)
    val shortTestTemp = providers.environmentVariable("FORGE_TEST_TMP").orNull
    if (shortTestTemp != null) systemProperty("java.io.tmpdir", shortTestTemp)
    doFirst {
        rootProject.file(".gradle/robolectric-home").mkdirs()
        if (shortTestTemp != null) rootProject.file(shortTestTemp).mkdirs()
    }
}
