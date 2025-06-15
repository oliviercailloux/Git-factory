package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitConfigTests {
  @SuppressWarnings("unused")
  private static final Logger LOGGER = LoggerFactory.getLogger(GitConfigTests.class);

  @Test
  void testGitConfig() throws Exception {
    StoredConfig u = SystemReader.getInstance().getUserConfig();
    assertEquals("Olivier Cailloux", u.getString("user", null, "name"));
    try (Git git = new Git(new InMemoryRepository(new DfsRepositoryDescription("test")))) {
      StoredConfig config = git.getRepository().getConfig();

      assertTrue(config.getSections().isEmpty(), "Expected config to have no sections");
      assertEquals(null, config.getString("user", null, "name"));
      assertEquals(null, config.getString("user", null, "email"));
      assertEquals(null, config.getString("core", null, "editor"));
    }
  }

  @Test
  void testConfigNotEmptied(@TempDir Path tempDir) throws Exception {
    StoredConfig config;
    try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
      config = git.getRepository().getConfig();
    }
    for (String section : config.getSections()) {
      {
        boolean changed = config.removeSection(section, null);
        assertEquals(section.equals("core"), changed);
      }
      for (String subsection : config.getSubsections(section)) {
        boolean changed = config.removeSection(section, subsection);
        assertFalse(changed);
      }
    }
    config.save();

    assertTrue(config.getSections().size() >= 6);
  }

  @Test
  void testClearConfig(@TempDir Path tempDir) throws Exception {
    FactoGit.clearConfig();
    try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
      StoredConfig config = git.getRepository().getConfig();
      assertEquals(ImmutableSet.of("core"), config.getSections());
      assertTrue(config.getSubsections("core").isEmpty(),
          "Expected core section to have no subsections");
      // assertEquals(ImmutableSet.of(…), config.getNames("core"));
      assertEquals(null, config.getString("user", null, "name"));
      assertEquals(null, config.getString("user", null, "email"));
      assertEquals(null, config.getString("core", null, "editor"));
    }
  }
}
