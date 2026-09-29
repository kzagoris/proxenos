plugins {
  `kotlin-dsl`
}

dependencies {
  // Carrying the Kotlin and Compose compiler plugins here is what lets every module apply
  // them by id with no version: the version lives in the catalog, once.
  implementation(libs.kotlin.gradlePlugin)
  implementation(libs.compose.compiler.gradlePlugin)
  implementation(libs.kotlin.serialization.gradlePlugin)

  testImplementation(kotlin("test"))
  testImplementation(gradleTestKit())
}

gradlePlugin {
  plugins {
    create("moduleBoundaries") {
      id = "proxenos.module-boundaries"
      implementationClass =
        "io.github.kzagoris.proxenos.buildlogic.ModuleBoundariesPlugin"
    }
  }
}

tasks.test {
  useJUnitPlatform()
}
