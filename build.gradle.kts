/*
 * ============================================================
 * YouTubeProvider – CloudStream3 Extension
 * ============================================================
 *
 * BUILD INSTRUCTIONS
 * ------------------
 * 1. Clone: git clone https://github.com/aniirudhhh/cloudstreamytexten.git
 *
 * 2. Set INVIDIOUS_BASE_URL in YouTubeProvider.kt (line ~30)
 *
 * 3. Run: ./gradlew :YouTubeProvider:make
 *
 * 4. Output: YouTubeProvider/build/outputs/YouTubeProvider.cs3
 *
 * 5. Sideload in CloudStream or add repo URL:
 *    https://raw.githubusercontent.com/aniirudhhh/cloudstreamytexten/builds/repo.json
 *
 * ============================================================
 */

import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        // CloudStream Gradle plugin — using explicit Git commit hash instead of SNAPSHOT to bypass Gradle metadata validation mismatch
        classpath("com.github.recloudstream.gradle:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
    }
}

// Make cloudstream {} DSL available to all sub-projects
fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

// Make android {} DSL available to all sub-projects
fun Project.android(configuration: BaseExtension.() -> Unit) =
    extensions.getByName<BaseExtension>("android").configuration()

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

subprojects {
    // Apply Android library + CloudStream plugins to every sub-project
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "aniirudhhh/cloudstreamytexten")
    }

    android {
        compileSdkVersion(35)
        defaultConfig {
            minSdk = 21
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    dependencies {
        // Provided by the CloudStream host app at runtime — do not bundle
        "compileOnly"("com.lagradost:cloudstream3:pre-release")
    }

    tasks.withType<KotlinJvmCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions",
            )
        }
    }
}
