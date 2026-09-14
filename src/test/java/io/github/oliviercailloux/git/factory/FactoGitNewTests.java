package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.ImmutableGraph;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.github.oliviercailloux.git.common.IdStamp;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class FactoGitNewTests {

  @Test
  void testSingleRoot() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path p = fs.getPath("c1");
      Files.createDirectories(p);
      Files.writeString(p.resolve("file.txt"), "hello");
      try (DfsRepository repo = FactoGitNew.empty().withRoot(p).repo()) {
        try (Git git = Git.wrap(repo)) {
          assertEquals(1, ImmutableList.copyOf(git.log().call()).size());
        }
        assertNotNull(repo.resolve("refs/heads/main"));
        assertNotNull(repo.resolve("HEAD"));
      }
    }
  }

  @Test
  void testLinearDag() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path a = fs.getPath("a");
      Path b = fs.getPath("b");
      Path c = fs.getPath("c");
      for (Path p : ImmutableList.of(a, b, c)) {
        Files.createDirectories(p);
        Files.writeString(p.resolve("f.txt"), p.getFileName().toString());
      }
      ImmutableGraph<Path> graph =
          GraphBuilder.directed().<Path>immutable().putEdge(a, b).putEdge(b, c).build();
      try (DfsRepository repo = FactoGitNew.ofDag(graph).repo()) {
        try (RevWalk rw = new RevWalk(repo)) {
          RevCommit head = rw.parseCommit(repo.resolve("refs/heads/main"));
          assertEquals(1, head.getParentCount());
          RevCommit mid = rw.parseCommit(head.getParent(0));
          assertEquals(1, mid.getParentCount());
          RevCommit root = rw.parseCommit(mid.getParent(0));
          assertEquals(0, root.getParentCount());
        }
      }
    }
  }

  @Test
  void testCommittersIterable() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path a = fs.getPath("a");
      Path b = fs.getPath("b");
      Files.createDirectories(a);
      Files.writeString(a.resolve("f.txt"), "a");
      Files.createDirectories(b);
      Files.writeString(b.resolve("f.txt"), "b");
      ImmutableGraph<Path> graph = GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
      IdStamp stampA =
          new IdStamp("Alice", "alice@example.com", Instant.EPOCH.atZone(ZoneOffset.UTC));
      IdStamp stampB = new IdStamp("Bob", "bob@example.com", Instant.EPOCH.atZone(ZoneOffset.UTC));
      // BFS visits a (root) first, so stampA → a, stampB → b
      try (DfsRepository repo =
          FactoGitNew.ofDag(graph).withCommitters(ImmutableList.of(stampA, stampB)).repo()) {
        try (RevWalk rw = new RevWalk(repo)) {
          RevCommit bCommit = rw.parseCommit(repo.resolve("refs/heads/main"));
          RevCommit aCommit = rw.parseCommit(bCommit.getParent(0));
          assertEquals("Alice", aCommit.getAuthorIdent().getName());
          assertEquals("Bob", bCommit.getAuthorIdent().getName());
        }
      }
    }
  }

  @Test
  void testMessagesIterable() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path a = fs.getPath("a");
      Path b = fs.getPath("b");
      Files.createDirectories(a);
      Files.writeString(a.resolve("f.txt"), "a");
      Files.createDirectories(b);
      Files.writeString(b.resolve("f.txt"), "b");
      ImmutableGraph<Path> graph = GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
      // BFS visits a first → "first", b second → "second"
      try (DfsRepository repo =
          FactoGitNew.ofDag(graph).withMessages(ImmutableList.of("first", "second")).repo()) {
        try (RevWalk rw = new RevWalk(repo)) {
          RevCommit bCommit = rw.parseCommit(repo.resolve("refs/heads/main"));
          RevCommit aCommit = rw.parseCommit(bCommit.getParent(0));
          assertEquals("first", aCommit.getShortMessage());
          assertEquals("second", bCommit.getShortMessage());
        }
      }
    }
  }

  @Test
  void testReplayability() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path a = fs.getPath("a");
      Path b = fs.getPath("b");
      Files.createDirectories(a);
      Files.writeString(a.resolve("f.txt"), "a");
      Files.createDirectories(b);
      Files.writeString(b.resolve("f.txt"), "b");
      ImmutableGraph<Path> graph = GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
      FactoGitNew fgn = FactoGitNew.ofDag(graph).withMessages(ImmutableList.of("msg1", "msg2"));
      ImmutableList<RevCommit> commits1;
      ImmutableList<RevCommit> commits2;
      try (DfsRepository repo = fgn.repo(); Git git = Git.wrap(repo)) {
        commits1 = ImmutableList.copyOf(git.log().call());
      }
      try (DfsRepository repo = fgn.repo(); Git git = Git.wrap(repo)) {
        commits2 = ImmutableList.copyOf(git.log().call());
      }
      assertEquals(commits1.size(), commits2.size());
      for (int i = 0; i < commits1.size(); i++) {
        assertEquals(commits1.get(i).getShortMessage(), commits2.get(i).getShortMessage());
      }
    }
  }

  @Test
  void testCommittersTFunction() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path a = fs.getPath("a");
      Path b = fs.getPath("b");
      Files.createDirectories(a);
      Files.writeString(a.resolve("f.txt"), "a");
      Files.createDirectories(b);
      Files.writeString(b.resolve("f.txt"), "b");
      ImmutableGraph<Path> graph = GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
      IdStamp stampA =
          new IdStamp("Alice", "alice@example.com", Instant.EPOCH.atZone(ZoneOffset.UTC));
      IdStamp stampB = new IdStamp("Bob", "bob@example.com", Instant.EPOCH.atZone(ZoneOffset.UTC));
      try (DfsRepository repo =
          FactoGitNew.ofDag(graph).withCommitters(p -> p.equals(a) ? stampA : stampB).repo()) {
        try (RevWalk rw = new RevWalk(repo)) {
          RevCommit bCommit = rw.parseCommit(repo.resolve("refs/heads/main"));
          RevCommit aCommit = rw.parseCommit(bCommit.getParent(0));
          assertEquals("Alice", aCommit.getAuthorIdent().getName());
          assertEquals("Bob", bCommit.getAuthorIdent().getName());
        }
      }
    }
  }

  @Test
  void testDefaultCommitterEpoch() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path p = fs.getPath("p");
      Files.createDirectories(p);
      Files.writeString(p.resolve("f.txt"), "hello");
      try (DfsRepository repo = FactoGitNew.empty().withRoot(p).repo()) {
        try (RevWalk rw = new RevWalk(repo)) {
          RevCommit commit = rw.parseCommit(repo.resolve("refs/heads/main"));
          assertEquals(Instant.EPOCH, commit.getCommitterIdent().getWhenAsInstant());
        }
      }
    }
  }

  @Test
  void testDefaultMessagesBfsOrder() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path a = fs.getPath("a");
      Path b = fs.getPath("b");
      Files.createDirectories(a);
      Files.writeString(a.resolve("f.txt"), "a");
      Files.createDirectories(b);
      Files.writeString(b.resolve("f.txt"), "b");
      // BFS from root a: a → "Commit number 1", b → "Commit number 2"
      ImmutableGraph<Path> graph = GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
      try (DfsRepository repo = FactoGitNew.ofDag(graph).repo()) {
        try (RevWalk rw = new RevWalk(repo)) {
          RevCommit bCommit = rw.parseCommit(repo.resolve("refs/heads/main"));
          RevCommit aCommit = rw.parseCommit(bCommit.getParent(0));
          assertEquals("Commit number 1", aCommit.getShortMessage());
          assertEquals("Commit number 2", bCommit.getShortMessage());
        }
      }
    }
  }

  /**
   * DAG structure: <pre>
   *   root1 ─── A ───┐
   *     │             ├── D ── E
   *     └─── B ──────┤
   *                   │
   *   root2 ─── C ───┘
   * </pre> BFS order (and thus commit-message assignment): root1, root2, A, B, C, D, E.
   *
   * <p>
   * Exercises: two roots; one root forking into two branches; modified, deleted, added, and moved
   * files; an empty file; a subdirectory; deep nesting; two commits with entirely empty trees (B
   * and E); a three-parent merge commit (D); a symlink (in D).
   */
  @Test
  void testComplexDag() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {

      // root1: four files covering all single-commit scenarios.
      Path root1 = fs.getPath("root1");
      Files.createDirectories(root1);
      Files.writeString(root1.resolve("keep.txt"), "Unchanged");
      Files.writeString(root1.resolve("modify.txt"), "Before modification");
      Files.writeString(root1.resolve("delete.txt"), "To be deleted");
      Files.writeString(root1.resolve("move_source.txt"), "Move me");

      // root2: a file and a subdirectory.
      Path root2 = fs.getPath("root2");
      Files.createDirectories(root2.resolve("subdir"));
      Files.writeString(root2.resolve("r2.txt"), "Root 2 file");
      Files.writeString(root2.resolve("subdir").resolve("r2_nested.txt"), "Nested in root 2");

      // A (child of root1): modify one file, delete one, add one, "move" one (delete source,
      // add dest with same content), create a subdirectory, and add an empty file.
      Path a = fs.getPath("a");
      Files.createDirectories(a.resolve("subdir"));
      Files.writeString(a.resolve("keep.txt"), "Unchanged");
      Files.writeString(a.resolve("modify.txt"), "After modification");
      // delete.txt absent → deleted relative to root1
      Files.writeString(a.resolve("move_dest.txt"), "Move me");
      // move_source.txt absent → moved away
      Files.writeString(a.resolve("subdir").resolve("nested.txt"), "Nested content");
      Files.writeString(a.resolve("empty.txt"), "");

      // B (child of root1): entirely empty tree — every file deleted.
      Path b = fs.getPath("b");
      Files.createDirectories(b);

      // C (child of root2): modified file and a deeply nested file.
      Path c = fs.getPath("c");
      Files.createDirectories(c.resolve("deep").resolve("subdir"));
      Files.writeString(c.resolve("r2.txt"), "Root 2 file modified");
      Files.writeString(c.resolve("deep").resolve("subdir").resolve("file.txt"),
          "Deep nested content");

      // D (child of A, B, C): merge commit with keep, a subdir file, a regular file, and a
      // symlink pointing to that regular file.
      Path d = fs.getPath("d");
      Files.createDirectories(d.resolve("subdir"));
      Files.writeString(d.resolve("keep.txt"), "Unchanged");
      Files.writeString(d.resolve("subdir").resolve("nested.txt"), "Updated nested");
      Files.writeString(d.resolve("final.txt"), "Final file");
      Files.createSymbolicLink(d.resolve("link.txt"), fs.getPath("final.txt"));

      // E (child of D): entirely empty tree again.
      Path e = fs.getPath("e");
      Files.createDirectories(e);

      // BFS order: root1, root2, A, B, C, D, E (7 commits).
      ImmutableGraph<Path> graph =
          GraphBuilder.directed().<Path>immutable().putEdge(root1, a).putEdge(root1, b)
              .putEdge(root2, c).putEdge(a, d).putEdge(b, d).putEdge(c, d).putEdge(d, e).build();

      FactoGitNew fgn = FactoGitNew.ofDag(graph)
          .withMessages(ImmutableList.of("root1", "root2", "A", "B", "C", "D", "E"));

      try (DfsRepository repo = fgn.repo()) {

        Map<String, RevCommit> commits = commitsByMessage(repo);
        assertEquals(7, commits.size());

        RevCommit cRoot1 = commits.get("root1");
        RevCommit cRoot2 = commits.get("root2");
        RevCommit cA = commits.get("A");
        final RevCommit cB = commits.get("B");
        final RevCommit cC = commits.get("C");
        final RevCommit cD = commits.get("D");
        final RevCommit cE = commits.get("E");
        assertNotNull(cRoot1, "root1");
        assertNotNull(cRoot2, "root2");
        assertNotNull(cA, "A");
        assertNotNull(cB, "B");
        assertNotNull(cC, "C");
        assertNotNull(cD, "D");
        assertNotNull(cE, "E");

        // HEAD points at E.
        assertEquals(cE.name(), repo.resolve("refs/heads/main").name());

        // root1 and root2: no parents.
        assertEquals(0, cRoot1.getParentCount());
        assertEquals(0, cRoot2.getParentCount());

        // root1 tree: four files, exact contents.
        Map<String, String> root1Contents = treeContents(repo, cRoot1);
        assertEquals(4, root1Contents.size());
        assertEquals("Unchanged", root1Contents.get("keep.txt"));
        assertEquals("Before modification", root1Contents.get("modify.txt"));
        assertEquals("To be deleted", root1Contents.get("delete.txt"));
        assertEquals("Move me", root1Contents.get("move_source.txt"));

        // root2 tree: a top-level file and one inside a subdirectory.
        Map<String, String> root2Contents = treeContents(repo, cRoot2);
        assertEquals(2, root2Contents.size());
        assertEquals("Root 2 file", root2Contents.get("r2.txt"));
        assertEquals("Nested in root 2", root2Contents.get("subdir/r2_nested.txt"));

        // A: single parent (root1); modifications, deletion, move, subdir, empty file.
        assertEquals(1, cA.getParentCount());
        assertEquals(cRoot1.name(), cA.getParent(0).name());
        Map<String, String> aContents = treeContents(repo, cA);
        assertEquals(5, aContents.size());
        assertEquals("Unchanged", aContents.get("keep.txt"));
        assertEquals("After modification", aContents.get("modify.txt"));
        assertFalse(aContents.containsKey("delete.txt"), "delete.txt should be absent");
        assertFalse(aContents.containsKey("move_source.txt"), "move_source.txt should be absent");
        assertEquals("Move me", aContents.get("move_dest.txt"));
        assertEquals("Nested content", aContents.get("subdir/nested.txt"));
        assertEquals("", aContents.get("empty.txt"));

        // B: single parent (root1); empty tree.
        assertEquals(1, cB.getParentCount());
        assertEquals(cRoot1.name(), cB.getParent(0).name());
        assertTrue(treeContents(repo, cB).isEmpty(), "B should have an empty tree");

        // C: single parent (root2); modified file and deeply nested file.
        assertEquals(1, cC.getParentCount());
        assertEquals(cRoot2.name(), cC.getParent(0).name());
        Map<String, String> cContents = treeContents(repo, cC);
        assertEquals(2, cContents.size());
        assertEquals("Root 2 file modified", cContents.get("r2.txt"));
        assertEquals("Deep nested content", cContents.get("deep/subdir/file.txt"));

        // D: three parents (A, B, C in edge-insertion order); four entries including a symlink.
        assertEquals(3, cD.getParentCount());
        assertEquals(Set.of(cA.name(), cB.name(), cC.name()),
            Set.of(cD.getParent(0).name(), cD.getParent(1).name(), cD.getParent(2).name()));
        Map<String, String> dContents = treeContents(repo, cD);
        assertEquals(4, dContents.size());
        assertEquals("Unchanged", dContents.get("keep.txt"));
        assertEquals("Updated nested", dContents.get("subdir/nested.txt"));
        assertEquals("Final file", dContents.get("final.txt"));
        assertEquals("final.txt", dContents.get("link.txt")); // symlink target stored as blob
        Map<String, FileMode> dModes = treeModes(repo, cD);
        assertEquals(FileMode.REGULAR_FILE, dModes.get("keep.txt"));
        assertEquals(FileMode.REGULAR_FILE, dModes.get("final.txt"));
        assertEquals(FileMode.SYMLINK, dModes.get("link.txt"));

        // E: single parent (D); empty tree again.
        assertEquals(1, cE.getParentCount());
        assertEquals(cD.name(), cE.getParent(0).name());
        assertTrue(treeContents(repo, cE).isEmpty(), "E should have an empty tree");
      }
    }
  }

  /**
   * Git requires tree entries to be sorted by its own rule: directories sort as if their name had a
   * trailing {@code /} appended. This differs from plain alphabetical order when a directory name
   * is a prefix of a file name: {@code '.'} (0x2E) {@literal <} {@code '/'} (0x2F), so
   * {@code "a.txt"} must come before directory {@code "a/"} in git order, but alphabetical order
   * puts {@code "a"} before {@code "a.txt"}.
   *
   * <p>
   * This test will fail until {@code insertTree} sorts entries by git's comparator before appending
   * them to the {@link org.eclipse.jgit.lib.TreeFormatter}.
   */
  @Test
  void testTreeEntryOrdering() throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path p = fs.getPath("commit");
      // "a.txt" must sort before directory "a/" in git order ('.' < '/'),
      // but alphabetical listing puts "a" before "a.txt".
      Files.createDirectories(p.resolve("a"));
      Files.writeString(p.resolve("a").resolve("nested.txt"), "nested");
      Files.writeString(p.resolve("a.txt"), "a");

      try (DfsRepository repo = FactoGitNew.empty().withRoot(p).repo()) {
        try (RevWalk rw = new RevWalk(repo)) {
          RevCommit commit = rw.parseCommit(repo.resolve("refs/heads/main"));
          try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(commit.getTree());
            tw.setRecursive(false);
            assertTrue(tw.next());
            assertEquals("a.txt", tw.getNameString()); // file before same-prefixed directory
            assertTrue(tw.next());
            assertEquals("a", tw.getNameString());
            assertFalse(tw.next());
          }
        }
      }
    }
  }

  @Test
  void testEmptyFileRepository(@TempDir Path gitDir) throws Exception {
    try (FileSystem fs = Jimfs.newFileSystem(Configuration.unix())) {
      Path contentDir = fs.getPath("content");
      Files.createDirectories(contentDir);
      Files.writeString(contentDir.resolve("hello.txt"), "hello");
      try (FileRepository repo = (FileRepository) FactoGitNew.empty().withRoot(contentDir)
          .repo(new FileRepositoryBuilder().setGitDir(gitDir.toFile()))) {
        assertNotNull(repo.resolve("refs/heads/main"));
        try (RevWalk rw = new RevWalk(repo)) {
          RevCommit commit = rw.parseCommit(repo.resolve("refs/heads/main"));
          assertEquals(0, commit.getParentCount());
          Map<String, String> contents = treeContents(repo, commit);
          assertEquals(Map.of("hello.txt", "hello"), contents);
        }
      }
    }
  }

  /** Returns all commits reachable from HEAD, keyed by short message. */
  private static Map<String, RevCommit> commitsByMessage(DfsRepository repo) throws Exception {
    Map<String, RevCommit> result = new HashMap<>();
    try (RevWalk rw = new RevWalk(repo)) {
      rw.markStart(rw.parseCommit(repo.resolve("refs/heads/main")));
      for (RevCommit c : rw) {
        result.put(c.getShortMessage(), c);
      }
    }
    return result;
  }

  /**
   * Returns a map from every file path in {@code commit}'s tree to its content (UTF-8 decoded, or
   * for symlinks, the target path string stored in the blob).
   */
  private static Map<String, String> treeContents(Repository repo, RevCommit commit)
      throws Exception {
    Map<String, String> result = new HashMap<>();
    try (TreeWalk tw = new TreeWalk(repo)) {
      tw.addTree(commit.getTree());
      tw.setRecursive(true);
      while (tw.next()) {
        byte[] bytes = tw.getObjectReader().open(tw.getObjectId(0)).getBytes();
        result.put(tw.getPathString(), new String(bytes, StandardCharsets.UTF_8));
      }
    }
    return result;
  }

  /** Returns a map from every file path in {@code commit}'s tree to its {@link FileMode}. */
  private static Map<String, FileMode> treeModes(Repository repo, RevCommit commit)
      throws Exception {
    Map<String, FileMode> result = new HashMap<>();
    try (TreeWalk tw = new TreeWalk(repo)) {
      tw.addTree(commit.getTree());
      tw.setRecursive(true);
      while (tw.next()) {
        result.put(tw.getPathString(), tw.getFileMode(0));
      }
    }
    return result;
  }
}
