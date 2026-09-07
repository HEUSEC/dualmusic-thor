import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Spotify client ID identifies one registration on developer.spotify.com, bound to
// a package name and a signing fingerprint, so a checkout of this repo cannot use the
// author's: keep it out of the sources and read it from local.properties (git-ignored)
// or the SPOTIFY_CLIENT_ID environment variable. Blank builds fine; Spotify is simply
// offered as unconfigured. It is a public client — PKCE, no secret — so this is about
// whose registration a build talks to, not about hiding a credential.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val spotifyClientId: String = (
    localProperties.getProperty("spotify.clientId")
        ?: System.getenv("SPOTIFY_CLIENT_ID")
        ?: ""
    ).trim()

// Release builds are signed with the key named in local.properties, which never enters
// the repository. Spotify ties its dashboard entry to the signing fingerprint, so a
// build signed with a key it does not know is refused at the consent screen: without a
// release key configured the build falls back to the debug one, which at least keeps a
// checkout runnable.
val releaseStore = localProperties.getProperty("release.storeFile")
    ?.let(::File)
    ?.takeIf { it.exists() }

android {
    namespace = "com.dualmusic.thor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dualmusic.thor"
        minSdk = 33
        targetSdk = 33
        versionCode = 3
        versionName = "1.1.0"

        buildConfigField("String", "SPOTIFY_CLIENT_ID", "\"$spotifyClientId\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = localProperties.getProperty("release.storePassword")
                keyAlias = localProperties.getProperty("release.keyAlias")
                keyPassword = localProperties.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: the Spotify SDK talks over a reflective protocol
            // and shrinking it is a separate piece of work with its own way of failing.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// The app's own UI and session handling use framework APIs only. The dependencies
// below exist solely for Spotify: its App Remote SDK is the only supported way to
// start playback of a chosen track from another app.
dependencies {
    // App Remote, from github.com/spotify/android-sdk (v0.8.0, 2023-07-04); Spotify
    // does not publish it to Maven. `build.sh` downloads it into app/libs. Their auth
    // library is deliberately absent: it cannot do PKCE, and it claimed the redirect
    // URI this app needs for itself.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("com.google.code.gson:gson:2.11.0")      // required by App Remote
    implementation("androidx.browser:browser:1.8.0")        // Custom Tabs, used by the auth lib
}
