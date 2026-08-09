package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link FastImporter#basic()}, {@link FastImporter#sub()} and
 * {@link FastImporter#linked()} reproduce the same tree content, commit-by-commit, as the
 * deprecated {@link FactoGit#setBasicDag()}, {@link FactoGit#setSubDag()} and
 * {@link FactoGit#setLinkedDag()} they are meant to replace. Identities and timestamps are
 * deliberately not compared: the legacy DAGs use empty identities and the current time, which the
 * new, deterministic fixtures do not attempt to reproduce. Blob and symlink-target content is
 * compared modulo a single trailing newline: the legacy DAGs write file content verbatim (no
 * trailing newline), while the new fixtures follow this project's fast-export fixture convention
 * of ending data blocks with one.
 */
public class LegacyDagFixturesTests {
  @Test
  void testBasicMatchesLegacy() throws Exception {
    assertSameContent(FactoGit::setBasicDag, FastImporter.basic());
  }

  @Test
  void testSubMatchesLegacy() throws Exception {
    assertSameContent(FactoGit::setSubDag, FastImporter.sub());
  }

  @Test
  void testLinkedMatchesLegacy() throws Exception {
    assertSameContent(FactoGit::setLinkedDag, FastImporter.linked());
  }

  private static void assertSameContent(Consumer<FactoGit> legacyDag, DfsRepository actual)
      throws IOException {
    final FactoGit f = FactoGit.empty();
    legacyDag.accept(f);
    try (Repository expected = f.build(); actual) {
      final ImmutableList<ImmutableMap<String, String>> expectedTrees = treesOf(expected);
      final ImmutableList<ImmutableMap<String, String>> actualTrees = treesOf(actual);
      assertEquals(expectedTrees, actualTrees);
    }
  }

  private static ImmutableList<ImmutableMap<String, String>> treesOf(Repository repository)
      throws IOException {
    final ImmutableList.Builder<ImmutableMap<String, String>> trees = ImmutableList.builder();
    try (RevWalk revWalk = new RevWalk(repository)) {
      revWalk.sort(RevSort.TOPO);
      revWalk.sort(RevSort.REVERSE, true);
      for (org.eclipse.jgit.lib.Ref ref : repository.getRefDatabase().getRefs()) {
        revWalk.markStart(revWalk.parseCommit(ref.getObjectId()));
      }
      for (RevCommit commit : revWalk) {
        final ImmutableMap.Builder<String, String> entries = ImmutableMap.builder();
        try (TreeWalk treeWalk = new TreeWalk(repository)) {
          treeWalk.addTree(commit.getTree());
          treeWalk.setRecursive(true);
          while (treeWalk.next()) {
            final byte[] bytes = repository.open(treeWalk.getObjectId(0)).getBytes();
            final String content = new String(bytes, StandardCharsets.UTF_8);
            entries.put(treeWalk.getPathString(),
                treeWalk.getFileMode(0) + ":" + stripTrailingNewline(content));
          }
        }
        trees.add(entries.build());
      }
    }
    return trees.build();
  }

  private static String stripTrailingNewline(String content) {
    return content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
  }
}
