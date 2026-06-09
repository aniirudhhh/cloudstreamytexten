/*
 * ============================================================
 * YouTubeProvider – CloudStream3 Extension
 * ============================================================
 *
 * BUILD INSTRUCTIONS
 * ------------------
 * 1. Clone this repository:
 *      git clone https://github.com/YOUR_USER/CloudStreamytEx.git
 *      cd CloudStreamytEx
 *
 * 2. Open YouTubeProvider/src/main/kotlin/com/example/youtubeprovider/YouTubeProvider.kt
 *    and set the INVIDIOUS_BASE_URL constant to your self-hosted instance, e.g.:
 *      const val INVIDIOUS_BASE_URL = "https://invidious.yourdomain.com"
 *    Also toggle PROXY_STREAMS to true/false depending on whether your instance
 *    proxies streams through itself (recommended for privacy).
 *
 * 3. Update the setRepo() fallback string in YouTubeProvider/build.gradle.kts
 *    to match your actual GitHub repository, e.g.:
 *      setRepo(System.getenv("GITHUB_REPOSITORY") ?: "YOUR_USER/CloudStreamytEx")
 *
 * 4. Build the extension .cs3 package:
 *      ./gradlew :YouTubeProvider:make
 *
 * 5. The compiled .cs3 file will be located at:
 *      YouTubeProvider/build/outputs/YouTubeProvider.cs3
 *    (The exact path is printed at the end of the Gradle task output.)
 *
 * 6. Sideload into CloudStream:
 *      • Locally:  Settings → Extensions → Load from file → select .cs3
 *      • Via repo: The CI workflow pushes the .cs3 and plugins.json to the
 *        "builds" branch automatically on every push to main/master.
 *
 * 7. Add the hosted repository to CloudStream:
 *      Settings → Extensions → Add repository →
 *        https://raw.githubusercontent.com/YOUR_USER/CloudStreamytEx/builds/repo.json
 *
 * ============================================================
 */

// Top-level build file; sub-project configuration lives in YouTubeProvider/build.gradle.kts.
buildscript {
    repositories {
        google()
        mavenCentral()
        // JitPack hosts both the CloudStream Gradle plugin and the cloudstream3 library.
        maven { url = uri("https://jitpack.io") }
    }

    dependencies {
        // CloudStream Gradle plugin — the -SNAPSHOT tag always resolves to the latest snapshot.
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        // Kotlin Gradle plugin — must match the kotlin() version used in sub-projects.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

// Wipe the root-level build directory on clean so stale .cs3 artefacts are removed.
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
