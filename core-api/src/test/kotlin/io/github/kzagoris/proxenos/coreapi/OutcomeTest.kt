package io.github.kzagoris.proxenos.coreapi

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OutcomeTest {
  @Test
  fun `an Uncertain outcome carries the words for the model and the reason for a frontend`() {
    val uncertain = Outcome.Uncertain(
      Uncertainty.RootBrokeMidOperation("api"),
      "This Operation did not complete. Its effects on disk are unknown — effects uncertain, do not retry.",
    )
    // The text is for the model; the sealed reason is for the frontends, which have to act.
    assertEquals(Uncertainty.RootBrokeMidOperation("api"), uncertain.reason)
    assertContains(uncertain.message, "effects uncertain, do not retry")
  }

  @Test
  fun `a paraphrase of the uncertainty is refused at the point it is written`() {
    // ADR 0001: failed and uncertain are the two a model will otherwise conflate, so an
    // adapter paraphrasing this into "operation failed" would reintroduce the retry hazard.
    assertFailsWith<IllegalArgumentException> {
      Outcome.Uncertain(Uncertainty.Stopped(emptyList()), "The operation failed.")
    }
  }
}
