package io.github.kzagoris.proxenos.buildlogic

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ModuleBoundaryRulesTest {
  @Test
  fun `a module that reaches only what it declared is clean`() {
    val violations = violations(
      subject = ":control",
      reached = setOf(":core-api"),
      allowed = setOf(":core-api"),
    )

    assertEquals(emptyList<String>(), violations)
  }

  @Test
  fun `reaching itself is never a violation`() {
    val violations = violations(
      subject = ":control",
      reached = setOf(":control", ":core-api"),
      allowed = setOf(":core-api"),
    )

    assertEquals(emptyList<String>(), violations)
  }

  @Test
  fun `an undeclared module is a violation, whether it was reached directly or through another`() {
    val violations = violations(
      subject = ":control",
      reached = setOf(":core-api", ":core", ":mcp"),
      allowed = setOf(":core-api"),
    )

    assertEquals(listOf(":core", ":mcp"), violations)
  }

  @Test
  fun `declaring a module it does not reach is not an error`() {
    val violations = violations(
      subject = ":tui",
      reached = setOf(":core-api"),
      allowed = setOf(":core-api", ":control"),
    )

    assertEquals(emptyList<String>(), violations)
  }

  @Test
  fun `the report does not list the module itself among what it reached`() {
    val report = boundaryReport(
      subject = ":control",
      violations = listOf(":core"),
      reached = setOf(":control", ":core-api", ":core"),
      allowed = setOf(":core-api"),
    )

    assertEquals(
      "  reached: :core, :core-api",
      report.lines().single { it.startsWith("  reached:") },
    )
  }

  @Test
  fun `the report names the subject, what it reached and what it was allowed`() {
    val report = boundaryReport(
      subject = ":control",
      violations = listOf(":core"),
      reached = setOf(":core-api", ":core"),
      allowed = setOf(":core-api"),
    )

    assertContains(report, ":control")
    assertContains(report, ":core")
    assertContains(report, "mayReach")
  }
}
