package org.wfanet.measurement.common

import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals

@RunWith(JUnit4::class)
class DuchyOrderTest {
  private val order = DuchyOrder(
    setOf(
      Duchy(BOHEMIA, 200L.toBigInteger()),
      Duchy(SALZBURG, 100L.toBigInteger()),
      Duchy(AUSTRIA, 300L.toBigInteger())
    )
  )

  @Test
  fun `first node first`() {
    assertEquals(listOf(SALZBURG, BOHEMIA, AUSTRIA), order.computationOrder(SHA1_MOD_3_IS_0))
  }

  @Test
  fun `second node first`() {
    assertEquals(listOf(BOHEMIA, AUSTRIA, SALZBURG), order.computationOrder(SHA1_MOD_3_IS_1))
  }

  @Test
  fun `third node first`() {
    assertEquals(listOf(AUSTRIA, SALZBURG, BOHEMIA), order.computationOrder(SHA1_MOD_3_IS_2))
  }

  @Test
  fun `test magic numbers`() {
    assertEquals(0, sha1Mod(SHA1_MOD_3_IS_0, 3.toBigInteger()))
    assertEquals(1, sha1Mod(SHA1_MOD_3_IS_1, 3.toBigInteger()))
    assertEquals(2, sha1Mod(SHA1_MOD_3_IS_2, 3.toBigInteger()))
  }

  companion object {
    private const val AUSTRIA = "Austria"
    private const val BOHEMIA = "Bohemia"
    private const val SALZBURG = "Salzburg"

    // These numbers were created with a bit of guess and check to find ones that matched
    // the desired test cases.
    private const val SHA1_MOD_3_IS_0 = 54L
    private const val SHA1_MOD_3_IS_1 = 311L
    private const val SHA1_MOD_3_IS_2 = 12L
  }
}
