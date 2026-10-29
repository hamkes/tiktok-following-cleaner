package com.hamdi.tiktokcleaner

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v2.0.6-gemini regression tests. Anchors: live log 2026-10-28 —
 * batch of 150, 25 unfollowed, ~125 skipped as Friends, same skips
 * re-checked first in the next batch.
 */
class TargetOrderTest {

    private fun result(username: String) = AuditResult(
        username = username,
        category = Category.DEAD,
        detail = "d",
        videoCount = 0,
        lastVideoMs = null,
        checkedAtMs = 0L
    )

    @Test
    fun skippedFriendsSinkLastInStableOrder() {
        val matching = listOf("a", "skip1", "b", "skip2", "c").map(::result)
        val ordered = TargetOrder.orderForUnfollow(matching, setOf("skip1", "skip2"))
        assertEquals(
            listOf("a", "b", "c", "skip1", "skip2"),
            ordered.map { it.username }
        )
    }

    @Test
    fun freshAccountsFillTheBatchFirst() {
        val matching = (1..10).map { result("u$it") } + listOf(result("oldSkip"))
        val ordered = TargetOrder.orderForUnfollow(matching, setOf("oldskip"))
        assertEquals((1..10).map { "u$it" } + "oldSkip", ordered.map { it.username })
        assertEquals((1..5).map { "u$it" }, ordered.take(5).map { it.username })
    }

    @Test
    fun emptySkippedSetKeepsOrder() {
        val matching = listOf("a", "b", "c").map(::result)
        assertEquals(matching, TargetOrder.orderForUnfollow(matching, emptySet()))
    }

    @Test
    fun lookupIsCaseInsensitive() {
        val matching = listOf(result("MixedCase"))
        val ordered = TargetOrder.orderForUnfollow(matching, setOf("mixedcase"))
        assertEquals(listOf("MixedCase"), ordered.map { it.username })
    }
}
