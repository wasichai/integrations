package wasichai.integrations.workflows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class FlowTest {
    // outer: fan -> [a, inner] meeting at join; inner: [b, c] meeting at innerJoin -> join; join -> done
    private val graph =
        WorkflowGraph(
            Trigger.Manual("fan"),
            listOf(
                Step.Parallel("fan", 0, listOf("a", "inner"), "join", null),
                Step.Delay("a", 1, Duration.ofSeconds(1), "join", null),
                Step.Parallel("inner", 2, listOf("b", "c"), "innerJoin", null),
                Step.Delay("b", 3, Duration.ofSeconds(1), "innerJoin", null),
                Step.Delay("c", 4, Duration.ofSeconds(1), "innerJoin", null),
                Step.Delay("innerJoin", 5, Duration.ofSeconds(1), "join", null),
                Step.Delay("join", 6, Duration.ofSeconds(1), "done", null),
                Step.End("done", 7)
            )
        )
    private val fan = graph.step("fan") as Step.Parallel
    private val inner = graph.step("inner") as Step.Parallel

    @Test
    fun `outside any parallel a target just runs`() {
        assertEquals(Move.Run("done", emptyList()), Flow.move(graph, "done", emptyList()))
    }

    @Test
    fun `a fan-out runs every branch in a frame of its own`() {
        assertEquals(
            listOf(Move.Run("a", listOf(Frame("fan", 0))), Move.Run("inner", listOf(Frame("fan", 1)))),
            Flow.fanOut(graph, fan, emptyList())
        )
        assertEquals(
            listOf(
                Move.Run("b", listOf(Frame("fan", 1), Frame("inner", 0))),
                Move.Run("c", listOf(Frame("fan", 1), Frame("inner", 1)))
            ),
            Flow.fanOut(graph, inner, listOf(Frame("fan", 1)))
        )
    }

    @Test
    fun `reaching the join is an arrival of that branch, and the join runs outside the parallel`() {
        assertEquals(Move.Arrive("join", "fan", 0, 2, emptyList()), Flow.move(graph, "join", listOf(Frame("fan", 0))))
        assertEquals(
            Move.Arrive("innerJoin", "inner", 1, 2, listOf(Frame("fan", 1))),
            Flow.move(graph, "innerJoin", listOf(Frame("fan", 1), Frame("inner", 1)))
        )
        // the inner join, run in the outer branch, then reaches the outer join
        assertEquals(Move.Arrive("join", "fan", 1, 2, emptyList()), Flow.move(graph, "join", listOf(Frame("fan", 1))))
        // a step inside a branch that is not the join runs in the same frames
        assertEquals(Move.Run("b", listOf(Frame("fan", 1))), Flow.move(graph, "b", listOf(Frame("fan", 1))))
    }

    @Test
    fun `jumping to an outer join from an inner branch arrives at the outer join and leaves the inner`() {
        assertEquals(Move.Arrive("join", "fan", 1, 2, emptyList()), Flow.move(graph, "join", listOf(Frame("fan", 1), Frame("inner", 0))))
    }

    @Test
    fun `the join completes once every branch has arrived, whatever the order, never on a repeat`() {
        assertFalse(Flow.completes(listOf(0), 2))
        assertFalse(Flow.completes(listOf(1, 1), 2))
        assertTrue(Flow.completes(listOf(1, 0), 2))
        assertTrue(Flow.completes(listOf(2, 0, 1), 3))
        assertFalse(Flow.completes(listOf(0, 2), 3))
        assertFalse(Flow.completes(emptyList(), 2))
    }
}
