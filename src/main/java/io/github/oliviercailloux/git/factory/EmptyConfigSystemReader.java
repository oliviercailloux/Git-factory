package io.github.oliviercailloux.git.factory;

import java.io.IOException;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.DfsConfig;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.SystemReader;

class EmptyConfigSystemReader extends SystemReader.Delegate {

  private StoredConfig empty;

  public EmptyConfigSystemReader() {
    super(SystemReader.getInstance());
    empty = new DfsConfig();
  }

  @Override
  public StoredConfig getUserConfig() {
    return empty;
  }

  @Override
  public StoredConfig getSystemConfig() {
    return empty;
  }

  @Override
  public StoredConfig getJGitConfig() {
    return empty;
  }
}
