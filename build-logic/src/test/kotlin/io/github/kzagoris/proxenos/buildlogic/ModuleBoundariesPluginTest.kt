package io.github.kzagoris.proxenos.buildlogic

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * The guardrail itself, run as a build. The fixture applies `java-library` rather than Kotlin
 * so these stay offline and quick: what is under test is the graph walk and the failure, not
 * anything about compiling Kotlin.
 */
class ModuleBoundariesPluginTest {
  private val projectDir: File = createTempDirectory("module-boundaries").toFile()

  @AfterTest
  fun cleanUp() {
    projectDir.deleteRecursively()
  }

  @Test
  fun `a module that reaches an undeclared project fails the build, and is told what it reached`() {
    directFixture(downstreamAllows = "")

    val result = runner(":downstream:checkModuleBoundaries").buildAndFail()

    assertContains(result.output, ":downstream reaches :upstream")
    assertContains(result.output, "mayReach")
  }

  @Test
  fun `declaring the project it reaches makes the build pass`() {
    directFixture(downstreamAllows = """mayReach(":upstream")""")

    val result = runner(":downstream:checkModuleBoundaries").build()

    assertTrue(
      result.task(":downstream:checkModuleBoundaries")?.outcome == TaskOutcome.SUCCESS,
    )
  }

  @Test
  fun `a project arriving transitively counts as reached`() {
    transitiveFixture(downstreamAllows = """mayReach(":middle")""")

    val result = runner(":downstream:checkModuleBoundaries").buildAndFail()

    assertContains(result.output, ":downstream reaches :upstream")
  }

  @Test
  fun `the check runs as part of check, not only when asked for by name`() {
    directFixture(downstreamAllows = "")

    val result = runner(":downstream:check").buildAndFail()

    assertContains(result.output, ":downstream reaches :upstream")
  }

  @Test
  fun `a module with no classpath to check fails rather than passing silently`() {
    settings(":downstream")
    write(
      "downstream/build.gradle.kts",
      """
        plugins { id("proxenos.module-boundaries") }
      """,
    )

    val result = runner(":downstream:checkModuleBoundaries").buildAndFail()

    assertContains(result.output, ":downstream has no classpath to check")
  }

  private fun runner(vararg arguments: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withPluginClasspath()
      .withArguments(*arguments)

  /** :downstream depends on :upstream directly. */
  private fun directFixture(downstreamAllows: String) {
    settings(":upstream", ":downstream")
    write("upstream/build.gradle.kts", """plugins { `java-library` }""")
    downstream(dependsOn = ":upstream", allows = downstreamAllows)
  }

  /** :downstream depends on :middle, which exposes :upstream through its api. */
  private fun transitiveFixture(downstreamAllows: String) {
    settings(":upstream", ":middle", ":downstream")
    write("upstream/build.gradle.kts", """plugins { `java-library` }""")
    write(
      "middle/build.gradle.kts",
      """
        plugins { `java-library` }
        dependencies { api(project(":upstream")) }
      """,
    )
    downstream(dependsOn = ":middle", allows = downstreamAllows)
  }

  private fun settings(vararg modules: String) {
    write(
      "settings.gradle.kts",
      """
        rootProject.name = "fixture"
        include(${modules.joinToString(", ") { "\"$it\"" }})
      """,
    )
  }

  private fun downstream(dependsOn: String, allows: String) {
    write(
      "downstream/build.gradle.kts",
      """
        plugins {
          `java-library`
          id("proxenos.module-boundaries")
        }
        dependencies { implementation(project("$dependsOn")) }
        moduleBoundaries { $allows }
      """,
    )
  }

  private fun write(path: String, contents: String) {
    val file = File(projectDir, path)
    file.parentFile.mkdirs()
    file.writeText(contents.trimIndent() + "\n")
  }
}
