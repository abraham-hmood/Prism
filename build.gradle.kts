plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.compose") version "1.7.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
}

// Shared in one place because :core and :app must resolve the SAME Room. They pull different
// variants of it -- jvm and android -- from the same coordinate, and a version skew between the
// two would mean the generated `_Impl` classes in :core were compiled against a different runtime
// than the one :app loads at runtime. That fails as a NoSuchMethodError on a user's device, not
// as a build error here.
extra["roomVersion"] = "2.7.2"
extra["sqliteVersion"] = "2.5.2"

