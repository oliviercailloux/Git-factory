package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableList;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;

public class FactoGitTests {
  @Test
  void testCreateRepoSub() throws Exception {
    final FactoGit f = FactoGit.empty();
    f.setSubDag();
    try (Repository repo = f.build()) {
      try (Git git = Git.wrap(repo)) {
        final ImmutableList<RevCommit> read = ImmutableList.copyOf(git.log().call());
        assertEquals(3, read.size());
      }
    }
  }

  @Test
  void testCreateBasic() throws Exception {
    final FactoGit f = FactoGit.empty();
    f.setBasicDag();
    f.setName("name");
    try (DfsRepository repo = f.build()) {
      try (Git git = Git.wrap(repo)) {
        final ImmutableList<RevCommit> read = ImmutableList.copyOf(git.log().call());
        assertEquals(1, read.size());
        assertEquals("name", repo.getDescription().getRepositoryName());
      }
    }
  }
}
