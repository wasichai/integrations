package wasichai.integrations.workflows

// one level of parallel fan-out a step runs inside: which parallel step, which of its branches
data class Frame(
    val parallel: String,
    val branch: Int
)

// where a run goes after a step, given the parallel branches it is inside (innermost last)
sealed interface Move {
    // run this step, inside these branches
    data class Run(
        val stepId: String,
        val scope: List<Frame>
    ) : Move

    // a branch of `parallel` reached its join; the join runs inside `scope` (the frames outside that parallel)
    data class Arrive(
        val join: String,
        val parallel: String,
        val branch: Int,
        val branches: Int,
        val scope: List<Frame>
    ) : Move
}

// how a run moves through the graph. pure, so the join bookkeeping is unit-tested without a database
object Flow {
    // the target is the join of a parallel the run is inside: an arrival for that branch. a branch that jumps to an
    // outer parallel's join leaves the inner ones (their join no longer counts it: ADR-0025)
    fun move(
        graph: WorkflowGraph,
        target: String,
        scope: List<Frame>
    ): Move {
        for (level in scope.indices.reversed()) {
            val frame = scope[level]
            val parallel = graph.step(frame.parallel) as Step.Parallel
            if (parallel.join == target) return Move.Arrive(target, parallel.id, frame.branch, parallel.branches.size, scope.subList(0, level).toList())
        }
        return Move.Run(target, scope)
    }

    // every branch of a parallel step, each in a frame of its own
    fun fanOut(
        graph: WorkflowGraph,
        parallel: Step.Parallel,
        scope: List<Frame>
    ): List<Move> = parallel.branches.mapIndexed { i, b -> move(graph, b, scope + Frame(parallel.id, i)) }

    // the join goes on once, when the arrival that completes the set is recorded: arrived holds the branches recorded
    // so far, this one included. a branch recorded twice is never counted twice
    fun completes(
        arrived: Collection<Int>,
        branches: Int
    ): Boolean = arrived.toSet() == (0 until branches).toSet()
}
