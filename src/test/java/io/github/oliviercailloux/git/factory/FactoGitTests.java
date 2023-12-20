package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.common.collect.ImmutableList;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;

public class FactoGitTests {
	@Test
	void testCreateRepo() throws Exception {
		final FactoGit f = FactoGit.empty();
		f.setSubDag();
		try (Repository repo = f.build()) {
			try (Git git = Git.wrap(repo)) {
				final ImmutableList<RevCommit> read = ImmutableList.copyOf(git.log().call());
				assertEquals(3, read.size());
			}
		}
	}
}
