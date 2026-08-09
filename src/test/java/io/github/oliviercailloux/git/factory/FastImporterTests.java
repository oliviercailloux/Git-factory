package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

public class FastImporterTests {
  @Test
  void testBasic() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("single-commit.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      assertEquals(0, commit.getParentCount());
      assertEquals("Initial commit\n", commit.getFullMessage());

      final PersonIdent author = commit.getAuthorIdent();
      assertEquals("Me", author.getName());
      assertEquals("me@example.com", author.getEmailAddress());
      assertEquals(Instant.ofEpochSecond(999985600), author.getWhenAsInstant());
      assertEquals(ZoneOffset.UTC, author.getZoneOffset());

      final PersonIdent committer = commit.getCommitterIdent();
      assertEquals("Me", committer.getName());
      assertEquals("me@example.com", committer.getEmailAddress());
      assertEquals(Instant.ofEpochSecond(999985600), committer.getWhenAsInstant());
      assertEquals(ZoneOffset.UTC, committer.getZoneOffset());

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("file.txt", treeWalk.getNameString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        final byte[] bytes = repo.open(treeWalk.getObjectId(0)).getBytes();
        assertEquals("Hello world\n", new String(bytes, StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testTwoCommits() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("two-commits.fast-export"));
        Git git = Git.wrap(repo)) {
      final ImmutableList<RevCommit> commits = ImmutableList.copyOf(git.log().call());
      assertEquals(2, commits.size());

      final RevCommit second = commits.get(0);
      final RevCommit first = commits.get(1);

      assertEquals("First commit\n", first.getFullMessage());
      assertEquals(0, first.getParentCount());
      assertEquals(Instant.ofEpochSecond(999985600), first.getAuthorIdent().getWhenAsInstant());

      assertEquals("Second commit\n", second.getFullMessage());
      assertEquals(1, second.getParentCount());
      assertEquals(first.getId(), second.getParent(0).getId());
      assertEquals(Instant.ofEpochSecond(999989200), second.getAuthorIdent().getWhenAsInstant());

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(first.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("file1.txt", treeWalk.getNameString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        assertEquals("Hello world\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(second.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("file1.txt", treeWalk.getNameString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        assertEquals("Hello world\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertTrue(treeWalk.next());
        assertEquals("file2.txt", treeWalk.getNameString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        assertEquals("Hello again\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testSubdirTimezone() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("subdir-timezone.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      assertEquals(ZoneOffset.ofHours(2), commit.getAuthorIdent().getZoneOffset());

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("src", treeWalk.getNameString());
        assertEquals(FileMode.TREE, treeWalk.getFileMode(0));
        assertFalse(treeWalk.next());
      }

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("src/Main.java", treeWalk.getPathString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        assertEquals("public class Main {}\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testExecutableMode() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("executable-mode.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("config.txt", treeWalk.getNameString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        assertTrue(treeWalk.next());
        assertEquals("run.sh", treeWalk.getNameString());
        assertEquals(FileMode.EXECUTABLE_FILE, treeWalk.getFileMode(0));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testTwoBranches() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("two-branches.fast-export"))) {
      final ObjectId mainId = repo.resolve("refs/heads/main");
      final ObjectId featureId = repo.resolve("refs/heads/feature");
      assertNotNull(mainId);
      assertNotNull(featureId);
      assertEquals(mainId, repo.resolve(Constants.HEAD));

      try (Git git = Git.wrap(repo)) {
        final ImmutableList<RevCommit> featureLog =
            ImmutableList.copyOf(git.log().add(featureId).call());
        assertEquals(2, featureLog.size());
        final RevCommit featureCommit = featureLog.get(0);
        final RevCommit mainCommit = featureLog.get(1);
        assertEquals("Feature commit\n", featureCommit.getFullMessage());
        assertEquals("Main commit\n", mainCommit.getFullMessage());
        assertEquals(mainId, featureCommit.getParent(0).getId());
        assertEquals(mainId, mainCommit.getId());
      }
    }
  }

  @Test
  void testMergeCommit() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("merge-commit.fast-export"));
        Git git = Git.wrap(repo)) {
      final ObjectId mainId = repo.resolve("refs/heads/main");
      final ObjectId featureId = repo.resolve("refs/heads/feature");

      final ImmutableList<RevCommit> mainLog =
          ImmutableList.copyOf(git.log().add(mainId).call());
      assertEquals(3, mainLog.size());

      final RevCommit mergeCommit = mainLog.get(0);
      assertEquals("Merge feature\n", mergeCommit.getFullMessage());
      assertEquals(2, mergeCommit.getParentCount());

      final ImmutableList<ObjectId> parentIds = ImmutableList.of(
          mergeCommit.getParent(0).getId(), mergeCommit.getParent(1).getId());
      final ObjectId mainCommitId = mainLog.get(2).getId();
      assertTrue(parentIds.contains(mainCommitId));
      assertTrue(parentIds.contains(featureId));

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(mergeCommit.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("extra.txt", treeWalk.getNameString());
        assertTrue(treeWalk.next());
        assertEquals("main.txt", treeWalk.getNameString());
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testDistinctCommitter() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("distinct-committer.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      final PersonIdent author = commit.getAuthorIdent();
      assertEquals("Alice", author.getName());
      assertEquals("alice@example.com", author.getEmailAddress());
      assertEquals(Instant.ofEpochSecond(999985600), author.getWhenAsInstant());
      final PersonIdent committer = commit.getCommitterIdent();
      assertEquals("Bob", committer.getName());
      assertEquals("bob@example.com", committer.getEmailAddress());
      assertEquals(Instant.ofEpochSecond(999989200), committer.getWhenAsInstant());
    }
  }

  @Test
  void testMultilevelDir() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("multilevel-dir.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("src/main/java/Main.java", treeWalk.getPathString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        assertEquals("public class Main {}\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testEncoding() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("encoding.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      assertEquals("Encoded commit\n", commit.getFullMessage());
      assertEquals("Me", commit.getAuthorIdent().getName());
    }
  }

  @Test
  void testSymlink() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("symlink.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("link.txt", treeWalk.getNameString());
        assertEquals(FileMode.SYMLINK, treeWalk.getFileMode(0));
        assertEquals("target.txt",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertTrue(treeWalk.next());
        assertEquals("target.txt", treeWalk.getNameString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testAnnotatedTag() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("annotated-tag.fast-export"))) {
      final ObjectId tagRefId = repo.resolve("refs/tags/v1.0");
      assertNotNull(tagRefId);
      try (RevWalk rw = new RevWalk(repo)) {
        final RevTag tag = rw.parseTag(tagRefId);
        assertEquals("Version 1.0\n", tag.getFullMessage());
        assertEquals("Me", tag.getTaggerIdent().getName());
        assertEquals("me@example.com", tag.getTaggerIdent().getEmailAddress());
        assertEquals(Instant.ofEpochSecond(999989200), tag.getTaggerIdent().getWhenAsInstant());
        final RevCommit commit = rw.parseCommit(tag.getObject().getId());
        assertEquals("Initial commit\n", commit.getFullMessage());
      }
    }
  }

  @Test
  void testGitlink() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("gitlink.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("main.txt", treeWalk.getNameString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        assertTrue(treeWalk.next());
        assertEquals("modules", treeWalk.getNameString());
        assertEquals(FileMode.TREE, treeWalk.getFileMode(0));
        assertFalse(treeWalk.next());
      }
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("main.txt", treeWalk.getNameString());
        assertTrue(treeWalk.next());
        assertEquals("modules/sub", treeWalk.getPathString());
        assertEquals(FileMode.GITLINK, treeWalk.getFileMode(0));
        assertEquals(ObjectId.fromString("da39a3ee5e6b4b0d3255bfef95601890afd80709"),
            treeWalk.getObjectId(0));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testUnicode() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.byteSource("unicode.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("file.txt", treeWalk.getNameString());
        final byte[] bytes = repo.open(treeWalk.getObjectId(0)).getBytes();
        assertEquals(6, bytes.length);
        assertEquals("café\n", new String(bytes, StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testPatchModifyDelete() throws Exception {
    try (DfsRepository repo =
        FastImporter.importRepository(Resourcer.charSource("patch-modify-delete.fast-export"));
        Git git = Git.wrap(repo)) {
      final ImmutableList<RevCommit> commits = ImmutableList.copyOf(git.log().call());
      assertEquals(2, commits.size());

      final RevCommit second = commits.get(0);
      final RevCommit first = commits.get(1);

      assertEquals("First commit\n", first.getFullMessage());
      assertEquals(0, first.getParentCount());

      assertEquals("Second commit\n", second.getFullMessage());
      assertEquals(1, second.getParentCount());
      assertEquals(first.getId(), second.getParent(0).getId());

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(first.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("file1.txt", treeWalk.getPathString());
        assertEquals("Hello world\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertTrue(treeWalk.next());
        assertEquals("keep.txt", treeWalk.getPathString());
        assertEquals("Keep this\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(second.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("file1.txt", treeWalk.getPathString());
        assertEquals("Hello again\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertFalse(treeWalk.next()); // keep.txt deleted
      }
    }
  }

  @Test
  void testPatchRename() throws Exception {
    try (DfsRepository repo =
        FastImporter.importRepository(Resourcer.charSource("patch-rename.fast-export"));
        Git git = Git.wrap(repo)) {
      final ImmutableList<RevCommit> commits = ImmutableList.copyOf(git.log().call());
      assertEquals(2, commits.size());

      final RevCommit second = commits.get(0);
      final RevCommit first = commits.get(1);

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(first.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("old.txt", treeWalk.getPathString());
        final byte[] content = repo.open(treeWalk.getObjectId(0)).getBytes();
        assertEquals("Hello world\n", new String(content, StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(second.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("new.txt", treeWalk.getPathString()); // renamed
        assertEquals("Hello world\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertFalse(treeWalk.next()); // old.txt gone
      }
    }
  }

  @Test
  void testTreeOrder() throws Exception {
    try (DfsRepository repo = FastImporter.importRepository(Resourcer.charSource("tree-order.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit second = git.log().call().iterator().next();
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(second.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("extra.txt", treeWalk.getPathString());
        assertTrue(treeWalk.next());
        assertEquals("file.txt", treeWalk.getPathString());
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testTreeOrderDirectoryVersusFile() throws Exception {
    try (DfsRepository repo =
        FastImporter.importRepository(Resourcer.charSource("tree-order-dir-vs-file.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("lib-old.txt", treeWalk.getNameString());
        assertEquals(FileMode.REGULAR_FILE, treeWalk.getFileMode(0));
        assertTrue(treeWalk.next());
        assertEquals("lib", treeWalk.getNameString());
        assertEquals(FileMode.TREE, treeWalk.getFileMode(0));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testFromBranchName() throws Exception {
    try (DfsRepository repo =
        FastImporter.importRepository(Resourcer.charSource("from-branch-name.fast-export"));
        Git git = Git.wrap(repo)) {
      final ImmutableList<RevCommit> commits = ImmutableList.copyOf(git.log().call());
      assertEquals(2, commits.size());

      final RevCommit second = commits.get(0);
      final RevCommit first = commits.get(1);
      assertEquals(1, second.getParentCount());
      assertEquals(first.getId(), second.getParent(0).getId());

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(second.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("file.txt", treeWalk.getPathString());
        assertTrue(treeWalk.next());
        assertEquals("later.txt", treeWalk.getPathString());
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testLightweightTag() throws Exception {
    try (DfsRepository repo =
        FastImporter.importRepository(Resourcer.charSource("lightweight-tag.fast-export"))) {
      final ObjectId mainId = repo.resolve("refs/heads/main");
      final ObjectId tagId = repo.resolve("refs/tags/v1.0-lw");
      assertNotNull(tagId);
      assertEquals(mainId, tagId);
      try (RevWalk rw = new RevWalk(repo)) {
        assertEquals(Constants.OBJ_COMMIT, rw.parseAny(tagId).getType());
      }
    }
  }

  @Test
  void testResetDeletesBranch() throws Exception {
    try (DfsRepository repo =
        FastImporter.importRepository(Resourcer.charSource("reset-delete-branch.fast-export"))) {
      assertNotNull(repo.resolve("refs/heads/main"));
      assertNull(repo.resolve("refs/heads/doomed"));
    }
  }

  @Test
  void testPatchRenameQuoted() throws Exception {
    try (DfsRepository repo =
        FastImporter.importRepository(Resourcer.charSource("patch-rename-quoted.fast-export"));
        Git git = Git.wrap(repo)) {
      final ImmutableList<RevCommit> commits = ImmutableList.copyOf(git.log().call());
      final RevCommit second = commits.get(0);

      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(second.getTree());
        treeWalk.setRecursive(true);
        assertTrue(treeWalk.next());
        assertEquals("new file.txt", treeWalk.getPathString());
        assertEquals("Hello world\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testQuotedPathWithSpace() throws Exception {
    try (DfsRepository repo =
        FastImporter.importRepository(Resourcer.charSource("quoted-path.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = Iterables.getOnlyElement(git.log().call());
      try (TreeWalk treeWalk = new TreeWalk(repo)) {
        treeWalk.addTree(commit.getTree());
        treeWalk.setRecursive(false);
        assertTrue(treeWalk.next());
        assertEquals("old file.txt", treeWalk.getNameString());
        assertEquals("Hello world\n",
            new String(repo.open(treeWalk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
        assertFalse(treeWalk.next());
      }
    }
  }

  @Test
  void testUnrecognizedCommandFailsFast() {
    assertThrows(RuntimeException.class, () -> FastImporter
        .importRepository(Resourcer.charSource("unrecognized-command.fast-export")));
  }

  @Test
  void testAliasCommandFailsFast() {
    assertThrows(RuntimeException.class,
        () -> FastImporter.importRepository(Resourcer.charSource("alias-command.fast-export")));
  }

  @Test
  void testNotemodifyFailsFast() {
    assertThrows(RuntimeException.class,
        () -> FastImporter.importRepository(Resourcer.charSource("notemodify.fast-export")));
  }

  @Test
  void testInlineFilemodifyFailsFast() {
    assertThrows(RuntimeException.class, () -> FastImporter
        .importRepository(Resourcer.charSource("inline-filemodify.fast-export")));
  }

  @Test
  void testShorthandModeFailsFast() {
    assertThrows(RuntimeException.class,
        () -> FastImporter.importRepository(Resourcer.charSource("shorthand-mode.fast-export")));
  }

  @Test
  void testDelimitedDataFailsFast() {
    assertThrows(RuntimeException.class,
        () -> FastImporter.importRepository(Resourcer.charSource("delimited-data.fast-export")));
  }
}
