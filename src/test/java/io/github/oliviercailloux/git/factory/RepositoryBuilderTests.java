package io.github.oliviercailloux.git.factory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RepositoryBuilderTests {

  @Test
  void buildBareOnNonExistingDirectory(@TempDir File parent) throws Exception {
    File dir = new File(parent, "new-repo");
    try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).setBare().build()) {
      assertFalse(repo.getObjectDatabase().exists());
    }
  }

  @Test
  void buildBareOnUninitializedDirectory(@TempDir File dir) throws Exception {
    try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).setBare().build()) {
      assertFalse(repo.getObjectDatabase().exists());
    }
  }

  @Test
  void createBareInitializesObjectDatabase(@TempDir File dir) throws Exception {
    try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).setBare().build()) {
      repo.create(repo.isBare());
      assertTrue(repo.getObjectDatabase().exists());
    }
  }

  @Test
  void buildBareOnInitializedDirectory(@TempDir File dir) throws Exception {
    try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).setBare().build()) {
      repo.create(repo.isBare());
    }
    try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).setBare().build()) {
      assertTrue(repo.getObjectDatabase().exists());
    }
  }

  @Test
  void createBareOnAlreadyInitializedDirectoryThrows(@TempDir File dir) throws Exception {
    try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).setBare().build()) {
      repo.create(repo.isBare());
    }
    try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).setBare().build()) {
      assertThrows(IllegalStateException.class, () -> repo.create(repo.isBare()));
    }
  }

  @Test
  void buildNonBareOnNonExistingDirectory(@TempDir File parent) throws Exception {
    File dir = new File(parent, "new-repo");
    try (Repository repo = new FileRepositoryBuilder().setWorkTree(dir).build()) {
      assertFalse(repo.getObjectDatabase().exists());
    }
  }

  @Test
  void buildNonBareOnUninitializedDirectory(@TempDir File dir) throws Exception {
    try (Repository repo = new FileRepositoryBuilder().setWorkTree(dir).build()) {
      assertFalse(repo.getObjectDatabase().exists());
    }
  }

  @Test
  void createNonBareInitializesObjectDatabase(@TempDir File dir) throws Exception {
    try (Repository repo = new FileRepositoryBuilder().setWorkTree(dir).build()) {
      repo.create(repo.isBare());
      assertTrue(repo.getObjectDatabase().exists());
    }
  }

  @Test
  void buildNonBareOnInitializedDirectory(@TempDir File dir) throws Exception {
    try (Repository repo = new FileRepositoryBuilder().setWorkTree(dir).build()) {
      repo.create(repo.isBare());
    }
    try (Repository repo = new FileRepositoryBuilder().setWorkTree(dir).build()) {
      assertTrue(repo.getObjectDatabase().exists());
    }
  }

  @Test
  void createNonBareOnAlreadyInitializedDirectoryThrows(@TempDir File dir) throws Exception {
    try (Repository repo = new FileRepositoryBuilder().setWorkTree(dir).build()) {
      repo.create(repo.isBare());
    }
    try (Repository repo = new FileRepositoryBuilder().setWorkTree(dir).build()) {
      assertThrows(IllegalStateException.class, () -> repo.create(repo.isBare()));
    }
  }

  /** create() checks only for the existence of the config file, nothing else. */
  @Test
  void createThrowsWhenConfigFileExistsAlone(@TempDir File dir) throws IOException {
    Files.createFile(dir.toPath().resolve("config"));
    try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).setBare().build()) {
      assertFalse(repo.getObjectDatabase().exists());
      assertThrows(IllegalStateException.class, () -> repo.create(repo.isBare()));
    }
  }

  @Test
  void createSucceedsWithUnrelatedFilesButNoConfigFile(@TempDir File dir) throws Exception {
    Files.createFile(dir.toPath().resolve("unrelated.txt"));
    try (Repository repo = new FileRepositoryBuilder().setGitDir(dir).setBare().build()) {
      repo.create(repo.isBare());
      assertTrue(repo.getObjectDatabase().exists());
    }
  }
}
