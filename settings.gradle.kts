// The six artifacts of SPEC §1. Their dependency directions are not a convention anyone has to
// remember: `proxenos.module-boundaries`, applied by the convention plugins in
// build-logic, fails the build when a module reaches something it has not declared.

pluginManagement {
  // Convention plugins live in an included build rather than buildSrc, so editing one does
  // not invalidate every module's build script cache.
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}

// Java toolchain auto-provisioning. Gradle has shipped no JDK downloader of its own since 7.6;
// it resolves a missing toolchain through a resolver plugin, and this is the Gradle team's.
// It only fires when no local JDK matches, so a build launched on a JDK 26 downloads nothing.
//
// The version is a literal because it has to be: Gradle evaluates this block before the
// version catalog below exists, so `libs` is not available here. It is the one version string
// outside gradle/libs.versions.toml, and it is not repeated anywhere.
plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    mavenCentral()
    // SPEC §12: `tui` needs this. Compose pulls androidx.lifecycle:lifecycle-runtime and
    // androidx.annotation:annotation, neither of which is on Maven Central. Leave it out
    // and the failure is a plain "Could not find androidx.lifecycle:lifecycle-runtime"
    // that says nothing about Compose.
    google()
  }
}

rootProject.name = "proxenos"

include(
  ":core-api",
  ":core",
  ":mcp",
  ":control",
  ":runtime",
  ":tui",
)
