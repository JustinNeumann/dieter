package com.getdieter.core.workspace

import com.getdieter.api.v1.ChangedFile
import com.getdieter.api.v1.Changeset
import com.getdieter.api.v1.FileDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Diff layout as both clients show it; ported from the macOS diff display tests. */
class DiffsTest {
    private fun rows(patch: String, split: Boolean = false, wholeCommit: Boolean = false) =
        DiffDisplay.rows(UnifiedDiff.parse(patch), split, wholeCommit)

    @Test
    fun aLayoutMeasuresLongCodeAndCountsEachHunkApart() {
        val longLine = "x".repeat(180) + "\t界"
        val patch =
            "diff --git a/source.swift b/source.swift\n@@ -1,2 +1,3 @@\n context\n-old\n+new\n+$longLine\n@@ -40 +41 @@\n-before\n+after"
        for (split in listOf(false, true)) {
            val layout = DiffLayout.of(UnifiedDiff.parse(patch), split)
            val hunks = layout.rows.filterIsInstance<DiffRow.Hunk>()
            assertEquals(2, hunks.size)
            assertEquals(2 to 1, hunks[0].additions to hunks[0].deletions)
            assertEquals(1 to 1, hunks[1].additions to hunks[1].deletions)
            assertTrue(
                layout.maxColumns >= 186,
                "a tab counts 4 and a wide character 2: ${layout.maxColumns}",
            )
        }
        assertSame(DiffLayout.EMPTY, DiffLayout.of(emptyList(), split = true))
    }

    @Test
    fun columnsCountScalarsOfCodeLinesOnly() {
        val lines =
            listOf(
                DiffLine(
                    0,
                    DiffLineKind.HUNK,
                    "@@ -1 +1 @@ a very long function context that is not code",
                ),
                DiffLine(1, DiffLineKind.ADDITION, "+😀\t"),
                DiffLine(2, DiffLineKind.CONTEXT, " ab"),
            )
        assertEquals(
            7,
            DiffDisplay.maximumColumns(lines),
            "an emoji is one scalar of 2 columns, the tab 4, the prefix 1",
        )
    }

    @Test
    fun theUnifiedLayoutDropsHeaderNoiseAndKeepsChanges() {
        val rows =
            rows(
                "diff --git a/a.swift b/a.swift\nindex 123..456 100644\n--- a/a.swift\n+++ b/a.swift\n@@ -1,3 +1,3 @@\n context\n-old\n+new"
            )
        assertEquals(1, rows.count { it is DiffRow.Hunk })
        val lines = rows.filterIsInstance<DiffRow.Line>().map { it.line }
        assertEquals(
            listOf(DiffLineKind.CONTEXT, DiffLineKind.DELETION, DiffLineKind.ADDITION),
            lines.map { it.kind },
        )
        assertTrue(rows.none { it is DiffRow.File }, "a single file's diff has no file rows")
    }

    @Test
    fun hunksCountTheUnchangedLinesBetweenThem() {
        val rows =
            rows(
                "@@ -10,4 +10,5 @@ func first()\n context\n-old\n+new\n+extra\n tail\n@@ -228,3 +229,4 @@ func second()\n context\n+added\n tail"
            )
        // The first hunk covers old lines 10..<14; the next opens at 228.
        assertEquals(
            listOf(0, 228 - 14),
            rows.filterIsInstance<DiffRow.Hunk>().map { it.skippedLines },
        )

        val context = (1..40).joinToString("\n") { " line $it" }
        val far = rows("@@ -1,41 +1,41 @@\n$context\n-a\n+b\n@@ -300,2 +300,2 @@\n x\n-y")
        assertEquals(listOf(0, 258), far.filterIsInstance<DiffRow.Hunk>().map { it.skippedLines })
    }

    @Test
    fun longContextRunsFoldDownToTheirEdges() {
        val context = (1..40).joinToString("\n") { " line $it" }
        val rows = rows("@@ -1,41 +1,41 @@\n$context\n-old\n+new")
        val fold = rows.filterIsInstance<DiffRow.Fold>().single()
        assertEquals(30, fold.count, "40 context lines keep a 5-line margin on each side")
        assertEquals(30, fold.lines.size)
        assertTrue(fold.pairs.isEmpty(), "the unified layout has no pairs")
        assertEquals(
            10,
            rows.filterIsInstance<DiffRow.Line>().count { it.line.kind == DiffLineKind.CONTEXT },
        )

        val split =
            rows("@@ -1,41 +1,41 @@\n$context\n-old\n+new", split = true)
                .filterIsInstance<DiffRow.Fold>()
                .single()
        assertEquals(fold.id, split.id, "a fold keeps its ID in either layout")
        assertEquals(30, split.pairs.size)
        assertTrue(split.pairs.all { it.old == it.new && it.old?.kind == DiffLineKind.CONTEXT })

        assertTrue(
            rows("@@ -1,6 +1,6 @@\n one\n two\n three\n-old\n+new\n four").none {
                it is DiffRow.Fold
            }
        )
    }

    @Test
    fun theSplitLayoutPairsDeletionsWithAdditions() {
        val pairs =
            rows("@@ -1,3 +1,4 @@\n context\n-removed\n+replaced\n+added\n tail", split = true)
                .filterIsInstance<DiffRow.Pair>()
        assertEquals(4, pairs.size)
        assertEquals(
            DiffLineKind.CONTEXT to DiffLineKind.CONTEXT,
            pairs[0].old?.kind to pairs[0].new?.kind,
            "context mirrors both sides",
        )
        assertEquals(
            DiffLineKind.DELETION to DiffLineKind.ADDITION,
            pairs[1].old?.kind to pairs[1].new?.kind,
        )
        assertEquals(
            null to DiffLineKind.ADDITION,
            pairs[2].old?.kind to pairs[2].new?.kind,
            "a surplus addition is one-sided",
        )
        assertEquals(DiffLineKind.CONTEXT, pairs[3].old?.kind)

        val more =
            rows("@@ -1,3 +1,4 @@\n-a\n-b\n+c\n+d\n+e\n x", split = true)
                .filterIsInstance<DiffRow.Pair>()
        assertEquals(listOf(true, true, false, true), more.map { it.old != null })
    }

    @Test
    fun aWholeCommitGetsARowPerFileAndGapsNeverSpanFiles() {
        val patch =
            "diff --git a/one.txt b/one.txt\n@@ -0,0 +1 @@\n+one\ndiff --git a/two.txt b/two.txt\n@@ -0,0 +1 @@\n+two"
        val rows = rows(patch, wholeCommit = true)
        assertEquals(
            listOf("one.txt", "two.txt"),
            rows.filterIsInstance<DiffRow.File>().map { it.path },
        )
        assertEquals(listOf(0, 0), rows.filterIsInstance<DiffRow.Hunk>().map { it.skippedLines })
        assertTrue(rows(patch).none { it is DiffRow.File })
        val paths =
            rows(
                "diff --git a/x b/src/x.kt\n@@ -1 +1 @@\n-a\n+b\ndiff --git a/y b/y.kt\n@@ -1 +1 @@\n-c\n+d",
                wholeCommit = true,
            )
        assertEquals(
            listOf("src/x.kt", "y.kt"),
            paths.filterIsInstance<DiffRow.File>().map { it.path },
        )
    }

    @Test
    fun hunkTextKeepsTheRangesAndTheFunctionContext() {
        assertEquals(
            "@@ -1284,9 +1284,16 @@ function ChatSidebar({ projects })",
            DiffDisplay.hunkText("@@ -1284,9 +1284,16 @@ function ChatSidebar({ projects })"),
        )
        assertEquals("@@ -1,3 +1,4 @@", DiffDisplay.hunkText("@@ -1,3 +1,4 @@"))
        assertEquals(
            "@@ -1 +1 @@ def f(): @@ x",
            DiffDisplay.hunkText("@@ -1 +1 @@  def f(): @@ x"),
            "the first @@ after the ranges closes them",
        )
        assertEquals(
            "@@ -1,41 +1,41 @@",
            rows("@@ -1,41 +1,41 @@\n-a\n+b").filterIsInstance<DiffRow.Hunk>().single().text,
        )
    }

    @Test
    fun hunkHeadersParseStrictly() {
        assertEquals(9, DiffDisplay.hunkSummary("@@ -1284,9 +1284,16 @@ fn")?.oldCount)
        assertEquals(HunkSummary(5, 1, 6, 1), DiffDisplay.hunkSummary("@@ -5 +6 @@"))
        assertEquals(
            1,
            DiffDisplay.hunkSummary("@@ -5,x +6 @@")?.oldCount,
            "an unreadable count is 1",
        )
        assertEquals(
            HunkSummary(10, 4, 10, 5),
            DiffDisplay.hunkSummary("@@  -10,4  +10,5 @@"),
            "repeated spaces separate once",
        )
        assertNull(
            DiffDisplay.hunkSummary("@@@ -1,2 -1,2 +1,3 @@@"),
            "a combined diff header is not a hunk header",
        )
        assertNull(DiffDisplay.hunkSummary("@@ -x +1 @@"))
        assertNull(DiffDisplay.hunkSummary("@@"))
    }

    @Test
    fun anUnreadableHunkHeaderBreaksTheGapChain() {
        val lines =
            listOf(
                DiffLine(0, DiffLineKind.HUNK, "@@ -10,4 +10,4 @@"),
                DiffLine(1, DiffLineKind.DELETION, "-a", oldLine = 10),
                DiffLine(2, DiffLineKind.HUNK, "@@ bogus @@"),
                DiffLine(3, DiffLineKind.ADDITION, "+b", newLine = 20),
                DiffLine(4, DiffLineKind.HUNK, "@@ -228,3 +229,3 @@"),
                DiffLine(5, DiffLineKind.DELETION, "-c", oldLine = 228),
            )
        assertEquals(
            listOf(0, 0, 0),
            DiffDisplay.rows(lines, split = false).filterIsInstance<DiffRow.Hunk>().map {
                it.skippedLines
            },
        )
    }

    @Test
    fun hunkDeltasCountChangesPerHunk() {
        assertEquals(
            mapOf(1 to (1 to 1)),
            DiffDisplay.hunkDeltas(UnifiedDiff.parse("h\n@@ -1 +1 @@\n-a\n+b")).filterKeys {
                it == 1
            },
        )
    }

    @Test
    fun changesetLineCountsComeFromEachFilesPatchAndReuseTheSameRevision() = runTest {
        val patches =
            mapOf(
                "a.kt" to
                    FileDiff(
                        patch =
                            "diff --git a/a.kt b/a.kt\n--- a/a.kt\n+++ b/a.kt\n@@ -1,2 +1,3 @@\n-old\n---still old\n+new\n+++still new\n+\n"
                    ),
                "icon.png" to FileDiff(binary = true, patch = "Binary files differ"),
            )
        val raw =
            Changeset(
                revision = "rev-1",
                files =
                    listOf(
                        ChangedFile(path = "a.kt", status = "modified", unstaged = true),
                        ChangedFile(path = "icon.png", status = "modified", unstaged = true),
                        ChangedFile(path = "broken.kt", status = "modified", unstaged = true),
                    ),
            )
        var calls = 0
        val counted =
            DiffPages.withLineCounts(raw) { file ->
                calls++
                if (file.path == "broken.kt") error("boom")
                patches[file.path]
            }
        assertEquals(3, calls)
        assertEquals(3 to 2, counted.files[0].let { it.additions to it.deletions })
        assertTrue(counted.files[1].binary)
        assertEquals(0 to 0, counted.files[2].let { it.additions to it.deletions })
        assertEquals(3 to 2, counted.additions to counted.deletions)

        // Same revision: the files with known counts or binary state are reused without re-reading.
        var secondCalls = 0
        val reused =
            DiffPages.withLineCounts(raw, previous = counted) { file ->
                secondCalls++
                FileDiff(patch = "@@ -1 +1 @@\n+${file.path}")
            }
        assertEquals(1, secondCalls, "only the uncounted file is retried on the same revision")
        assertEquals(3 to 2, reused.files[0].let { it.additions to it.deletions })
        assertTrue(reused.files[1].binary)
        assertEquals(1 to 0, reused.files[2].let { it.additions to it.deletions })
        assertEquals(4 to 2, reused.additions to reused.deletions)
    }
}
