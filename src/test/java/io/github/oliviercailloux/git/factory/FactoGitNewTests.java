package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.common.collect.ImmutableList;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.ImmutableGraph;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.github.oliviercailloux.git.common.IdStamp;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;

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
      ImmutableGraph<Path> graph =
          GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
      IdStamp stampA =
          new IdStamp("Alice", "alice@example.com", Instant.EPOCH.atZone(ZoneOffset.UTC));
      IdStamp stampB =
          new IdStamp("Bob", "bob@example.com", Instant.EPOCH.atZone(ZoneOffset.UTC));
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
      ImmutableGraph<Path> graph =
          GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
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
      ImmutableGraph<Path> graph =
          GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
      FactoGitNew fgn =
          FactoGitNew.ofDag(graph).withMessages(ImmutableList.of("msg1", "msg2"));
      ImmutableList<RevCommit> commits1, commits2;
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
      ImmutableGraph<Path> graph =
          GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
      IdStamp stampA =
          new IdStamp("Alice", "alice@example.com", Instant.EPOCH.atZone(ZoneOffset.UTC));
      IdStamp stampB =
          new IdStamp("Bob", "bob@example.com", Instant.EPOCH.atZone(ZoneOffset.UTC));
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
      ImmutableGraph<Path> graph =
          GraphBuilder.directed().<Path>immutable().putEdge(a, b).build();
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
}
