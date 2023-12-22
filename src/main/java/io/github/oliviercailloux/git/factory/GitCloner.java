package io.github.oliviercailloux.git.factory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.MoreCollectors;
import io.github.oliviercailloux.git.common.GitUri;
import io.github.oliviercailloux.jaris.exceptions.Unchecker;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.PullResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GitCloner {
  @SuppressWarnings("unused")
  private static final Logger LOGGER = LoggerFactory.getLogger(GitCloner.class);
  @SuppressWarnings("unused")
  private static final Unchecker<GitAPIException, IllegalStateException> UNCHECKER =
      Unchecker.wrappingWith(IllegalStateException::new);

  public static GitCloner create() {
    return new GitCloner();
  }

  private GitCloner() {}

  public void clone(GitUri gitUri, Repository repo) {
    try (Git git = Git.wrap(repo)) {
      git.fetch().setRemote(gitUri.asString())
          .setRefSpecs(new RefSpec("+refs/heads/*:refs/heads/*")).call();
    } catch (GitAPIException e) {
      throw new IllegalStateException(e);
    }
  }

  public FileRepository download(GitUri uri, Path workTree) {
    for (int i = 0; i < 1; ++i) {
      try {
        return downloadGeneral(uri, workTree, false);
      } catch (GitAPIException e) {
        LOGGER.error("Oops, retrying temporarily.", e);
      }
    }
    throw new IllegalStateException("Failed.");
  }

  public FileRepository downloadBare(GitUri uri, Path gitDir) {
    try {
      return downloadGeneral(uri, gitDir, true);
    } catch (GitAPIException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * If the given uri contains an empty repository, this returns an empty repository:
   * repository.getObjectDatabase().exists() is true; repository.getRefDatabase().hasRefs() is
   * false.
   *<p>
   * TODO I should probably not attempt to create a bare repository where a non-bare repository
   * currently lives!
   *
   * @param repositoryDirectory GIT_DIR (replacing .git dir) if bare (see {@link FileRepository}),
   *        otherwise, work tree dir, in which a .git dir will be created (or exists).
   * @param allowBare {@code true} to clone bare if not exists (if exists, this method will not
   *        check whether it is bare)
   * @return the repository
   * @throws GitAPIException if some git command fails
   */
  private FileRepository downloadGeneral(GitUri uri, Path repositoryDirectory, boolean allowBare)
      throws GitAPIException {
    final FileRepository repository;
    final boolean exists = Files.exists(repositoryDirectory);
    LOGGER.info("Downloading to {}, exists? {}.", repositoryDirectory, exists);
    if (!exists) {
      final CloneCommand cloneCmd = Git.cloneRepository();
      cloneCmd.setURI(uri.asString());
      cloneCmd.setBare(allowBare);
      final File dest = repositoryDirectory.toFile();
      cloneCmd.setDirectory(dest);
      LOGGER.info("Cloning {} to {}.", uri, dest);
      // try {
      // cloneCmd.call().close();
      // } catch (GitAPIException e) {
      // throw new IOException(e);
      // }
      // try (Git git = Git.open(repositoryDirectory.toFile())) {
      Git git = cloneCmd.call();
      repository = (FileRepository) git.getRepository();
    } else {
      try {
        repository = (FileRepository) new FileRepositoryBuilder()
            .setWorkTree(repositoryDirectory.toFile()).build();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      try (Git git = Git.wrap(repository)) {
        final List<RemoteConfig> remoteList = git.remoteList().call();
        final Optional<RemoteConfig> origin = remoteList.stream()
            .filter((r) -> r.getName().equals("origin")).collect(MoreCollectors.toOptional());
        final Status status = git.status().call();
        /*
         * Creates a problem probably related to https://stackoverflow.com/a/4162672
         * (oliviercailloux-org/eclipse-LucasLePort).
         *
         * Also, if a non-bare repository is there and the caller tries to download bare by handing
         * the .git folder, this reports an unclean status because it thinks that I want to version
         * the files in the .git folder (objects/pack/*, refs/*, …).
         */
        if (!git.getRepository().isBare() && !status.isClean()) {
          throw new IllegalStateException(
              "Can’t update " + uri + ": not clean (" + toString(status) + ").");
        }
        final String fullBranch = git.getRepository().getFullBranch();
        LOGGER.debug("HEAD: {}.", fullBranch);
        if (origin.isPresent() && origin.get().getURIs().size() == 1
            && origin.get().getURIs().get(0).toString().equals(uri.asString())) {
          final FetchResult fetchResult = git.fetch().call();
          if (git.getRepository().isBare()) {
            final String messages = fetchResult.getMessages();
            if (!messages.isEmpty()) {
              LOGGER.error("Fetch result: {}.", messages);
              throw new IllegalStateException("Fetch failed (perhaps)");
            }
          } else {
            final Ref r = fetchResult.getAdvertisedRef(fullBranch);
            if (r == null) {
              /* Happens with a repository on GitHub that has never been pushed to. */
              /*
               * TODO happens if I manually check out an older commit from the locally clone
               * repository.
               */
              LOGGER.info("Did not pull, remote server did not advertise {}.", fullBranch);
            } else {
              final PullResult pullResult = git.pull().call();
              if (!pullResult.isSuccessful()) {
                LOGGER.error("Pull failed with results: {}, {}, {}.", pullResult.getFetchResult(),
                    pullResult.getMergeResult(), pullResult.getRebaseResult());
                throw new IllegalStateException("Merge failed");
              }
            }
          }
        } else {
          throw new IllegalStateException("Unexpected remote: " + remoteList);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return repository;
  }

  private String toString(Status status) {
    return MoreObjects.toStringHelper(status).add("Added", status.getAdded())
        .add("Changed", status.getChanged()).add("Conflicting", status.getConflicting())
        .add("Ignored not in index", status.getIgnoredNotInIndex())
        .add("Missing", status.getMissing()).add("Modified", status.getModified())
        .add("Removed", status.getRemoved())
        .add("Uncommitted changes", status.getUncommittedChanges())
        .add("Untracked", status.getUntracked()).toString();
  }
}
