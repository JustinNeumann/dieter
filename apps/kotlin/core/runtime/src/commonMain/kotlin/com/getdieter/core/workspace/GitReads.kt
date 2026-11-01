package com.getdieter.core.workspace

import com.getdieter.api.v1.ChangedFile
import com.getdieter.api.v1.Changeset
import com.getdieter.api.v1.FileDiff
import com.getdieter.api.v1.GetDiffRequest
import com.getdieter.api.v1.GitOperation
import com.getdieter.api.v1.GitOperationRef
import com.getdieter.core.runtime.Deadlines
import com.getdieter.core.session.MachineSessions
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Paged diffs, shared by the review and project changes surfaces. A diff loads [PAGE] bytes at a
 * time and shows at most [LIMIT] bytes; a larger one stays truncated and says so instead of loading
 * more.
 */
object DiffPages {
    const val PAGE = 1 shl 20
    const val LIMIT = 4L * PAGE
    const val TOO_LARGE = "This diff is too large to show in full; its first 4 MB are shown."
    const val COUNTED_FILES = 150
    private const val COUNT_CONCURRENCY = 4

    /** Whether another page of [diff] may load. */
    fun hasMore(diff: FileDiff?): Boolean = diff?.truncated == true && diff.next_offset < LIMIT

    /**
     * Populates per-file and total line counts on [changes] when the daemon's porcelain status
     * snapshot omitted them. Reuses counts from [previous] while [Changeset.revision] is unchanged,
     * and reads at most [COUNTED_FILES] files ([COUNT_CONCURRENCY] at a time) through [diff].
     */
    suspend fun withLineCounts(
        changes: Changeset,
        previous: Changeset? = null,
        diff: suspend (ChangedFile) -> FileDiff?,
    ): Changeset {
        if (changes.files.isEmpty()) return changes
        val priorByPath =
            if (
                previous != null &&
                    previous.revision.isNotEmpty() &&
                    previous.revision == changes.revision
            )
                previous.files.associateBy { Triple(it.path, it.staged, it.unstaged) }
            else emptyMap()
        val gate = Semaphore(COUNT_CONCURRENCY)
        val files = coroutineScope {
            changes.files
                .mapIndexed { index, file ->
                    async {
                        val prior = priorByPath[Triple(file.path, file.staged, file.unstaged)]
                        when {
                            file.additions != 0 || file.deletions != 0 || file.binary -> file
                            prior != null &&
                                (prior.additions != 0 || prior.deletions != 0 || prior.binary) ->
                                file.copy(
                                    additions = prior.additions,
                                    deletions = prior.deletions,
                                    binary = prior.binary,
                                )
                            index >= COUNTED_FILES || file.path.isEmpty() -> file
                            else -> {
                                val page =
                                    gate.withPermit {
                                        try {
                                            diff(file)
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Throwable) {
                                            null
                                        }
                                    } ?: return@async file
                                val (added, removed) = UnifiedDiff.counts(page.patch)
                                file.copy(
                                    additions = added,
                                    deletions = removed,
                                    binary = file.binary || page.binary,
                                )
                            }
                        }
                    }
                }
                .awaitAll()
        }
        val added = files.sumOf { it.additions }
        val removed = files.sumOf { it.deletions }
        return changes.copy(
            files = files,
            additions =
                if (changes.additions != 0 || changes.deletions != 0) changes.additions else added,
            deletions =
                if (changes.additions != 0 || changes.deletions != 0) changes.deletions else removed,
        )
    }

    /**
     * Reads the first page of [request] from [daemonId], or the page after [loaded]; the result
     * carries every page read so far.
     */
    suspend fun read(
        sessions: MachineSessions,
        daemonId: String,
        request: GetDiffRequest,
        loaded: FileDiff? = null,
    ): FileDiff {
        val paged = request.copy(offset = loaded?.next_offset ?: 0, limit = PAGE)
        val page =
            sessions.call(daemonId, Deadlines.READ) { client ->
                if (paged.commit_sha.isNotEmpty()) client.GetCommitDiff().execute(paged)
                else client.GetFileDiff().execute(paged)
            }
        return if (loaded != null) page.copy(patch = loaded.patch + page.patch) else page
    }
}

/** How long a surface follows one Git operation before it stops waiting. */
private val GIT_OPERATION_LIMIT: Duration = 1.hours

/**
 * Follows [operation] on [daemonId] until [settled] accepts it: every [interval] it takes the state
 * [streamed] already holds for it, else reads it. Stops when [keep] turns false or after
 * [GIT_OPERATION_LIMIT] and returns the last state seen. A failed read ends the wait unless
 * [retryReads], which tries again at the next interval.
 */
suspend fun MachineSessions.followGitOperation(
    daemonId: String,
    operation: GitOperation,
    interval: Duration,
    keep: () -> Boolean = { true },
    settled: (GitOperation) -> Boolean = { it.status !in GitOperations.ACTIVE },
    streamed: () -> GitOperation? = { null },
    retryReads: Boolean = false,
    onRead: (GitOperation) -> Unit = {},
): GitOperation {
    var current = operation
    val started = TimeSource.Monotonic.markNow()
    while (keep() && !settled(current) && started.elapsedNow() < GIT_OPERATION_LIMIT) {
        delay(interval)
        current =
            streamed()?.takeIf { it.id == operation.id && settled(it) }
                ?: try {
                    call(daemonId, Deadlines.READ) {
                        it.GetGitOperation().execute(GitOperationRef(operation_id = operation.id))
                    }
                } catch (error: Throwable) {
                    if (error is CancellationException || !retryReads) throw error
                    continue
                }
        onRead(current)
    }
    return current
}
