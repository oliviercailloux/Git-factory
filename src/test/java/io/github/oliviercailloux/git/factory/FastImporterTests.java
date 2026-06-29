package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableList;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

public class FastImporterTests {
  @Test
  void testMergeCommit() throws Exception {
    try (DfsRepository repo = FastImporter.create()
        .importRepository(Resourcer.charSource("merge-commit.fast-export"));
        Git git = Git.wrap(repo)) {
      final var mainId = repo.resolve("refs/heads/main");
      final var featureId = repo.resolve("refs/heads/feature");

      final ImmutableList<RevCommit> mainLog =
          ImmutableList.copyOf(git.log().add(mainId).call());
      assertEquals(3, mainLog.size());

      final RevCommit mergeCommit = mainLog.get(0);
      assertEquals("Merge feature\n", mergeCommit.getFullMessage());
      assertEquals(2, mergeCommit.getParentCount());

      final var parentIds = ImmutableList.of(
          mergeCommit.getParent(0).getId(), mergeCommit.getParent(1).getId());
      final var mainCommitId = mainLog.get(2).getId();
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
  void testTwoBranches() throws Exception {
    try (DfsRepository repo = FastImporter.create()
        .importRepository(Resourcer.charSource("two-branches.fast-export"))) {
      final var mainId = repo.resolve("refs/heads/main");
      final var featureId = repo.resolve("refs/heads/feature");
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
  void testExecutableMode() throws Exception {
    try (DfsRepository repo = FastImporter.create()
        .importRepository(Resourcer.charSource("executable-mode.fast-export"));
        Git git = Git.wrap(repo)) {
      final RevCommit commit = ImmutableList.copyOf(git.log().call()).get(0);

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
  void testSubdirTimezone() throws Exception {
    try (DfsRepository repo = FastImporter.create()
        .importRepository(Resourcer.charSource("subdir-timezone.fast-export"));
        Git git = Git.wrap(repo)) {
      final ImmutableList<RevCommit> commits = ImmutableList.copyOf(git.log().call());
      assertEquals(1, commits.size());

      final RevCommit commit = commits.get(0);
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
  void testTwoCommits() throws Exception {
    try (DfsRepository repo = FastImporter.create()
        .importRepository(Resourcer.charSource("two-commits.fast-export"));
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
  void testBasic() throws Exception {
    try (DfsRepository repo = FastImporter.create()
        .importRepository(Resourcer.charSource("basic.fast-export"));
        Git git = Git.wrap(repo)) {
      final ImmutableList<RevCommit> commits = ImmutableList.copyOf(git.log().call());
      assertEquals(1, commits.size());

      final RevCommit commit = commits.get(0);
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
}
