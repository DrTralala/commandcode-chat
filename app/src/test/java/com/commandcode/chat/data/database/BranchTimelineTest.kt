package com.commandcode.chat.data.database

import com.commandcode.chat.domain.MessageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchTimelineTest {
    @Test
    fun selectedWalksCreationOrderedNestedChoicesAndAnnotatesTheSelectedAnchor() {
        val timeline = fixture()

        val visible = timeline.selected("c")

        assertEquals(listOf("u", "a2", "u2", "a2-nested"), visible.map { it.id })
        val choice = visible.single { it.id == "a2" }.branchChoice!!
        assertEquals(listOf("a1", "a2", "a3"), choice.messageIds)
        assertEquals(1, choice.selectedIndex)
        assertEquals(listOf("a2"), visible.filter { it.branchChoice != null }.map { it.id })
    }

    @Test
    fun sourceReturnsOnlyAncestorPlacementsAndTheFullSelectedPathToken() {
        val source = fixture().source("c", "a2")

        assertEquals(listOf("u"), source.prefix.map { it.id })
        assertEquals("a2", source.target.id)
        assertEquals(listOf("root", "u", "a2", "u2", "a2-nested"), source.expectedPathIds)
    }

    @Test
    fun missingSelectionForAnExistingChildGroupIsAnIntegrityFailure() {
        val broken = fixture(selections = listOf(BranchSelectionEntity("root", "u")))

        assertTrue(runCatching { broken.selected("c") }.isFailure)
    }

    @Test
    fun cyclesAreRejectedInsteadOfConcatenatingHistory() {
        val broken = BranchTimeline(
            nodes = listOf(
                TimelineNodeEntity("root", "c", null, 0),
                TimelineNodeEntity("u", "c", "a", 0),
                TimelineNodeEntity("a", "c", "u", 0),
            ),
            placements = listOf(
                MessagePlacementEntity("u", "u", 0),
                MessagePlacementEntity("a", "a", 0),
            ),
            selections = listOf(BranchSelectionEntity("root", "u"), BranchSelectionEntity("u", "a")),
            messages = listOf(
                message("u", "USER", "u"),
                message("a", "ASSISTANT", "a"),
            ),
        )

        assertTrue(runCatching { broken.selected("c") }.isFailure)
    }

    @Test
    fun selectedFailedResponseUsesTheUserResponseSelectorAndProjectsFailure() {
        val timeline = BranchTimeline(
            nodes = listOf(
                TimelineNodeEntity("root", "c", null, 0),
                TimelineNodeEntity("u", "c", "root", 0),
                TimelineNodeEntity("failed", "c", "u", 0),
                TimelineNodeEntity("good", "c", "u", 1),
            ),
            placements = listOf(
                MessagePlacementEntity("u", "u", 0),
                MessagePlacementEntity("failed", "failed", 0),
                MessagePlacementEntity("good", "good", 0),
            ),
            selections = listOf(
                BranchSelectionEntity("root", "u"),
                BranchSelectionEntity("u", "failed"),
            ),
            messages = listOf(
                message("u", "USER", "u").copy(status = "FAILED_ZDR"),
                message("failed", "ASSISTANT", "", "u").copy(status = "FAILED_ZDR"),
                message("good", "ASSISTANT", "good", "u"),
            ),
        )

        val user = timeline.selected("c").single { it.id == "u" }

        assertEquals("FAILED_ZDR", user.status)
        assertEquals(listOf("failed", "good"), user.responseBranchChoice!!.messageIds)
        assertEquals(0, user.responseBranchChoice!!.selectedIndex)
    }

    @Test
    fun selectedDeletedResponseProjectsACompleteUserWithoutAnAssistantBubble() {
        val timeline = BranchTimeline(
            nodes = listOf(
                TimelineNodeEntity("root", "c", null, 0),
                TimelineNodeEntity("u", "c", "root", 0),
                TimelineNodeEntity("deleted", "c", "u", 0, anchorDeleted = true),
                TimelineNodeEntity("good", "c", "u", 1),
            ),
            placements = listOf(
                MessagePlacementEntity("u", "u", 0),
                MessagePlacementEntity("good", "good", 0),
            ),
            selections = listOf(
                BranchSelectionEntity("root", "u"),
                BranchSelectionEntity("u", "deleted"),
            ),
            messages = listOf(
                message("u", "USER", "u").copy(status = "FAILED_ZDR"),
                message("good", "ASSISTANT", "good", "u"),
            ),
        )

        val visible = timeline.selected("c")

        assertEquals(listOf("u"), visible.map { it.id })
        assertEquals("COMPLETE", visible.single().status)
        assertEquals(listOf("deleted", "good"), visible.single().responseBranchChoice!!.messageIds)
    }

    private fun fixture(
        selections: List<BranchSelectionEntity> = listOf(
            BranchSelectionEntity("root", "u"),
            BranchSelectionEntity("u", "a2"),
            BranchSelectionEntity("a2", "u2"),
            BranchSelectionEntity("u2", "a2-nested"),
        ),
    ) = BranchTimeline(
        nodes = listOf(
            TimelineNodeEntity("root", "c", null, 0),
            TimelineNodeEntity("u", "c", "root", 0),
            TimelineNodeEntity("a1", "c", "u", 0),
            TimelineNodeEntity("a2", "c", "u", 1),
            TimelineNodeEntity("a3", "c", "u", 2),
            TimelineNodeEntity("u2", "c", "a2", 0),
            TimelineNodeEntity("a2-nested", "c", "u2", 0),
        ),
        placements = listOf(
            MessagePlacementEntity("u", "u", 0),
            MessagePlacementEntity("a1", "a1", 0),
            MessagePlacementEntity("a1-notice", "a1", 1),
            MessagePlacementEntity("a2", "a2", 0),
            MessagePlacementEntity("a3", "a3", 0),
            MessagePlacementEntity("a3-notice", "a3", 1),
            MessagePlacementEntity("u2", "u2", 0),
            MessagePlacementEntity("a2-nested", "a2-nested", 0),
        ),
        selections = selections,
        messages = listOf(
            message("u", "USER", "u"),
            message("a1", "ASSISTANT", "a1", "u"),
            message("a1-notice", MessageState.MODEL_CHANGE, "a1-model"),
            message("a2", "ASSISTANT", "a2", "u"),
            message("a3", "ASSISTANT", "a3", "u"),
            message("a3-notice", MessageState.SYSTEM_PROMPT_CHANGE, "a3-prompt"),
            message("u2", "USER", "u2"),
            message("a2-nested", "ASSISTANT", "a2-nested", "u2"),
        ),
    )

    private fun message(id: String, role: String, content: String, replyTo: String? = null) =
        Message(id, "c", role, content, null, 10L, "COMPLETE", replyToMessageId = replyTo)
}
