/**
 * Sub-project build file for the YouTubeProvider CloudStream extension.
 *
 * The CloudStream Gradle plugin (com.lagradost.cloudstream3.gradle) adds the
 * `cloudstream {}` DSL block and the `make` task that bundles everything into a
 * .cs3 artefact ready for sideloading or repository hosting.
 */
// WHY: The plugins {} block resolves through the Gradle Plugin Portal, which has no
// knowledge of JitPack-hosted plugins. The CloudStream plugin lives only on JitPack,
// so it must be applied via apply(plugin=...) which uses the buildscript classpath
// populated in the root build.gradle.kts. The Android and Kotlin plugins stay in
// plugins {} because they ARE available through standard channels.
plugins {
    id("com.android.library")
    kotlin("android") version "2.3.0"
}

// Apply the CloudStream Gradle plugin from the buildscript classpath (JitPack).
// This adds the cloudstream {} DSL extension and the :make task.
apply(plugin = "com.lagradost.cloudstream3.gradle")


// ---------------------------------------------------------------------------
// CloudStream extension metadata
// ---------------------------------------------------------------------------
cloudstream {
    /**
     * setRepo() writes the repository URL into the generated plugins.json so that
     * CloudStream knows where to look for updates.
     *
     * We read GITHUB_REPOSITORY from the CI environment (set by GitHub Actions as
     * "owner/repo"). The fallback string lets local builds produce a valid plugins.json
     * — replace it with your actual repository before publishing.
     */
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "aniirudhhh/cloudstreamytexten")

    /**
     * pluginClassName must exactly match the simple class name of the class annotated
     * with @CloudstreamPlugin (YouTubeProviderPlugin). CloudStream uses reflection to
     * instantiate this class at runtime; a mismatch will silently fail to load.
     */
    pluginClassName = "YouTubeProviderPlugin"
}

// ---------------------------------------------------------------------------
// Android library configuration
// ---------------------------------------------------------------------------
android {
    // Target Android 15 (API 35) for foreground-service exemptions and predictive back.
    compileSdk = 35

    defaultConfig {
        minSdk = 21        // Minimum supported CloudStream target; covers ~97 % of active devices.
        targetSdk = 35

        // Extension version — bump this integer whenever you release a new build so
        // CloudStream's update checker can notify users.
        version = 1
    }

    compileOptions {
        // CloudStream's pre-release library is compiled against JVM 8 bytecode, so we
        // must stay at 1.8 to avoid NoSuchMethodError crashes at runtime.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

// ---------------------------------------------------------------------------
// Dependencies
// ---------------------------------------------------------------------------
repositories {
    google()
    mavenCentral()
    // JitPack hosts both cloudstream3 pre-release and the CloudStream Gradle plugin.
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    /**
     * compileOnly: the CloudStream host app already ships cloudstream3 at runtime.
     * Bundling it would bloat the .cs3 and cause duplicate-class errors.
     * "pre-release" resolves to the latest snapshot suitable for extension development.
     */
    compileOnly("com.lagradost:cloudstream3:pre-release")
}
