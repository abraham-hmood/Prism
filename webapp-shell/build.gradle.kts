plugins {
    id("com.android.application")
}

/**
 * The template behind the browser's "Install as app". PRISM BUILDS IT, NOT THE USER.
 *
 * ## What it is
 *
 * One activity whose layout is a full-screen WebView, and nothing else: no Kotlin, no AndroidX, no
 * libraries. That keeps the APK small, because Prism ships a copy of it inside its own assets and
 * every installed web app is a full copy of it.
 *
 * ## How a web app is made from it
 *
 * Prism takes this APK (built unsigned), rewrites the package name and the label in its compiled
 * manifest, adds `assets/webapp.properties` naming the site, signs the result with a key generated
 * on the phone, and installs it. See `com.prism.launcher.browser.WebAppBuilder`. For that to work:
 *
 *   - the label is a LITERAL in the manifest, never `@string/...`, so it lives in the manifest's own
 *     string pool where it can be rewritten (a resource would be in resources.arsc);
 *   - the package name below is a placeholder that is never installed as-is;
 *   - code never derives anything from the package name, which changes per site.
 */
android {
    namespace = "com.prism.webshell"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prism.webshell.template"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Nothing to shrink: one small class. Leaving R8 off also keeps the class name the
            // manifest names exactly as written.
            isMinifyEnabled = false
            // UNSIGNED ON PURPOSE. Prism signs each web app on the phone with its own key; a
            // signature from this build would only be stripped and replaced.
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The template is never shipped on its own, so release lint checks aimed at a published
        // app (a launcher icon in every density, a store listing) do not apply to it.
        checkReleaseBuilds = false
    }
}
