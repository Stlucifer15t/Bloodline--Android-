// Root project configuration.
//
// The root project intentionally declares no plugins of its own: every plugin is
// applied by the module that needs it. This keeps the build readable and makes it
// cheap to add new modules later (for example a standalone `runtime` module that
// wraps the Wine/Box86 native stack).

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
