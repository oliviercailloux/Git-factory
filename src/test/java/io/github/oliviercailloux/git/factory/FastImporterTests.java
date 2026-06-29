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
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;

public class FastImporterTests {
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
