// What every one of the six artifacts is: a Kotlin library on the JDK 26 toolchain, with its
// dependency directions policed. Applying the Kotlin plugin by id with no version works because
// build-logic carries kotlin-gradle-plugin on its classpath at the catalog's version.

import io.github.kzagoris.proxenos.buildlogic.ModuleBoundariesPlugin
import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
  id("org.jetbrains.kotlin.jvm")
}

// Not in the plugins block above: a precompiled script plugin cannot apply a plugin its own
// project produces, because the marker does not exist yet when this file is compiled.
apply<ModuleBoundariesPlugin>()

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
  // SPEC §12. A toolchain, not sourceCompatibility: the JVM that launches Gradle is then
  // free to be anything, and what we compile against stays 26 on every machine.
  jvmToolchain(libs.findVersion("jdk").get().requiredVersion.toInt())
}

dependencies {
  testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}
