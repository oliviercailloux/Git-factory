package io.github.oliviercailloux.git.factory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static io.github.oliviercailloux.jaris.exceptions.Unchecker.IO_UNCHECKER;

import com.google.common.base.Verify;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.graph.Graph;
import com.google.common.graph.Graphs;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.github.oliviercailloux.jaris.collections.GraphUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectDatabase;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Please switch to FactoGit. */
@Deprecated
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
public class JGit {
  @SuppressWarnings("unused")
  private static final Logger LOGGER = LoggerFactory.getLogger(JGit.class);

  public static InMemoryRepository createRepository(PersonIdent personIdent, Graph<Path> baseDirs,
      Path links) throws IOException {
    return createRepository(Maps.asMap(baseDirs.nodes(), p -> personIdent), baseDirs, links);
  }

  public static InMemoryRepository createRepository(Map<Path, PersonIdent> identities,
      Graph<Path> baseDirs, Path links) throws IOException {
    checkArgument(!Graphs.hasCycle(baseDirs));
    checkArgument(identities.keySet().equals(baseDirs.nodes()));

    final ImmutableSet<Path> starters = baseDirs.nodes().stream()
        .filter(p -> baseDirs.predecessors(p).size() == 0).collect(ImmutableSet.toImmutableSet());
    LOGGER.debug("Visiting from {}.", starters);
    final Iterable<Path> sources = GraphUtils.topologicallySortedNodes(baseDirs);

    final InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription("myrepo"));
    // IO_UNCHECKER.call(() -> repository.create(true));
    try (ObjectDatabase objectDatabase = repository.getObjectDatabase()) {

      final BiMap<Path, ObjectId> commitsBuilder = HashBiMap.create(baseDirs.nodes().size());
      try (ObjectInserter inserter = objectDatabase.newInserter()) {
        for (Path source : sources) {
          LOGGER.debug("Visiting {}.", source);
          final Set<Path> parentPaths = baseDirs.predecessors(source);
          final ImmutableList<ObjectId> parents = parentPaths.stream()
              .map(p -> commitsBuilder.get(p)).collect(ImmutableSet.toImmutableSet()).asList();
          final ObjectId oId =
              insertCommit(inserter, identities.get(source), source, parents, "First commit");
          commitsBuilder.put(source, oId);
        }
      }
      final ImmutableBiMap<Path, ObjectId> commits = ImmutableBiMap.copyOf(commitsBuilder);

      for (Path link : Files.find(links, 10, (p, a) -> true).filter(p -> !Files.isDirectory(p))
          .collect(ImmutableSet.toImmutableSet())) {
        final Path targetPath = Files.readSymbolicLink(link);
        final ObjectId targetId = commits.get(targetPath);
        final Path relativeLinkName = links.relativize(link);
        LOGGER.debug("Linking {} to {}.", relativeLinkName, targetPath);
        final RefUpdate update =
            repository.getRefDatabase().newUpdate(relativeLinkName.toString(), false);
        update.setNewObjectId(targetId);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        final Result result = update.update();
        checkState(result.equals(Result.NEW));
        // Git.wrap(repository).branchCreate().setName("origin/" + link.getFileName().toString())
        // .setStartPoint(targetId.getName()).call();
      }
    }

    return repository;
  }

  public static InMemoryRepository createRepository(PersonIdent personIdent, String path,
      String content) {
    final InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription("myrepo"));
    IO_UNCHECKER.call(() -> repository.create(true));
    final ObjectDatabase objectDatabase = repository.getObjectDatabase();

    try (FileSystem jimFs = Jimfs.newFileSystem(Configuration.unix());
        ObjectInserter inserter = objectDatabase.newInserter()) {
      final Path workDir = jimFs.getPath("");

      final Path target = workDir.resolve(path);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content);
      final ObjectId commitStart =
          insertCommit(inserter, personIdent, workDir, ImmutableList.of(), "First commit");

      setMain(repository, commitStart);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    return repository;
  }

  public static ImmutableList<ObjectId> createBasicRepo(Repository repository) throws IOException {
    // repository.getConfig().setString("init", null, "defaultBranch", "main");
    repository.create(true);
    final ObjectDatabase objectDatabase = repository.getObjectDatabase();

    try (FileSystem jimFs = Jimfs.newFileSystem(Configuration.unix());
        ObjectInserter inserter = objectDatabase.newInserter()) {
      final Path workDir = jimFs.getPath("");
      final PersonIdent personIdent = new PersonIdent("Me", "email");
      final ImmutableList.Builder<ObjectId> builder = ImmutableList.builder();

      Files.writeString(workDir.resolve("file1.txt"), "Hello, world");
      final ObjectId commitStart =
          insertCommit(inserter, personIdent, workDir, ImmutableList.of(), "First commit");
      builder.add(commitStart);

      Files.writeString(workDir.resolve("file2.txt"), "Hello again");
      final ObjectId commitNext = insertCommit(inserter, personIdent, workDir,
          ImmutableList.of(commitStart), "Second commit");
      builder.add(commitNext);

      final ImmutableList<ObjectId> commits = builder.build();

      setMain(repository, commits.get(commits.size() - 1));

      return commits;
    }
  }

  public static ImmutableList<ObjectId> createRepoWithLink(Repository repository)
      throws IOException {
    repository.create(true);
    final ObjectDatabase objectDatabase = repository.getObjectDatabase();

    try (FileSystem jimFs = Jimfs.newFileSystem(Configuration.unix());
        ObjectInserter inserter = objectDatabase.newInserter()) {
      final Path workDir = jimFs.getPath("");
      final PersonIdent personIdent = new PersonIdent("Me", "email");
      final ImmutableList.Builder<ObjectId> builder = ImmutableList.builder();

      final Path file1 = workDir.resolve("file1.txt");
      Files.writeString(file1, "Hello, world");
      final Path link = workDir.resolve("link.txt");
      Files.createSymbolicLink(link, file1);
      final Path absoluteLink = workDir.resolve("absolute link");
      Files.createSymbolicLink(absoluteLink, workDir.resolve("/absolute"));
      verify(Files.isSymbolicLink(link));
      final ObjectId commitStart =
          insertCommit(inserter, personIdent, workDir, ImmutableList.of(), "First commit");
      builder.add(commitStart);

      Files.writeString(file1, "Hello instead");
      final ObjectId commit2 = insertCommit(inserter, personIdent, workDir,
          ImmutableList.of(commitStart), "Second commit");
      builder.add(commit2);

      final Path subDirectory = workDir.resolve("dir");
      Files.createDirectory(subDirectory);
      Files.createSymbolicLink(subDirectory.resolve("link"), jimFs.getPath("../link.txt"));
      Files.createSymbolicLink(subDirectory.resolve("linkToParent"), jimFs.getPath(".."));
      Files.createSymbolicLink(subDirectory.resolve("cyclingLink"),
          jimFs.getPath("../dir/cyclingLink"));
      final ObjectId commit3 =
          insertCommit(inserter, personIdent, workDir, ImmutableList.of(commit2), "Third commit");
      builder.add(commit3);

      Files.delete(file1);
      final ObjectId commit4 =
          insertCommit(inserter, personIdent, workDir, ImmutableList.of(commit3), "Fourth commit");
      builder.add(commit4);

      final ImmutableList<ObjectId> commits = builder.build();

      setMain(repository, commits.get(commits.size() - 1));

      return commits;
    }
  }

  public static ImmutableList<ObjectId> createRepoWithSubDir(Repository repository)
      throws IOException {
    return createRepoWithSubDir(repository, ZonedDateTime.now(ZoneId.of("Europe/Paris")));
  }

  public static ImmutableList<ObjectId> createRepoWithSubDir(Repository repository,
      ZonedDateTime created) throws IOException {
    repository.create(true);
    final ObjectDatabase objectDatabase = repository.getObjectDatabase();
    try (FileSystem jimFs = Jimfs.newFileSystem(Configuration.unix());
        ObjectInserter inserter = objectDatabase.newInserter()) {
      final Path workDir = jimFs.getPath("");

      final Instant created1 = created.toInstant();
      final Instant created2 = created1.plus(1, ChronoUnit.HOURS);
      final Instant created3 = created2.plus(2, ChronoUnit.HOURS);
      final PersonIdent personIdent1 = new PersonIdent("Me", "email", Date.from(created1),
          TimeZone.getTimeZone(created.getZone()));
      final PersonIdent personIdent2 = new PersonIdent(personIdent1, Date.from(created2));
      final PersonIdent personIdent3 = new PersonIdent(personIdent1, Date.from(created3));

      final ImmutableList.Builder<ObjectId> builder = ImmutableList.builder();

      Files.writeString(workDir.resolve("file1.txt"), "Hello, world");
      final ObjectId commit1 =
          insertCommit(inserter, personIdent1, workDir, ImmutableList.of(), "First commit");
      builder.add(commit1);

      Files.writeString(workDir.resolve("file2.txt"), "Hello again");
      final ObjectId commit2 =
          insertCommit(inserter, personIdent2, workDir, ImmutableList.of(commit1), "Second commit");
      builder.add(commit2);

      Files.writeString(workDir.resolve("file2.txt"), "I insist");
      final Path subDirectory = workDir.resolve("dir");
      Files.createDirectory(subDirectory);
      Files.writeString(subDirectory.resolve("file.txt"), "Hello from sub dir");
      final ObjectId commit3 =
          insertCommit(inserter, personIdent3, workDir, ImmutableList.of(commit2), "Third commit");
      builder.add(commit3);

      final ImmutableList<ObjectId> commits = builder.build();

      setMain(repository, commits.get(commits.size() - 1));

      return commits;
    }
  }

  private static ObjectId insertCommit(ObjectInserter inserter, PersonIdent personIdent,
      Path directory, List<ObjectId> parents, String commitMessage) throws IOException {
    final ObjectId treeId = insertTree(inserter, directory);
    return insertCommit(inserter, personIdent, treeId, parents, commitMessage);
  }

  private static ObjectId insertCommit(ObjectInserter inserter, PersonIdent personIdent,
      ObjectId treeId, List<ObjectId> parents, String commitMessage) throws IOException {
    final CommitBuilder commitBuilder = new CommitBuilder();
    commitBuilder.setMessage(commitMessage);
    commitBuilder.setAuthor(personIdent);
    commitBuilder.setCommitter(personIdent);
    commitBuilder.setTreeId(treeId);
    for (ObjectId parent : parents) {
      commitBuilder.addParentId(parent);
    }
    final ObjectId commitId = inserter.insert(commitBuilder);
    inserter.flush();
    LOGGER.debug("Created commit: {}.", commitId);
    return commitId;
  }

  /**
   * Inserts a tree containing the content of the given directory.
   * <p>
   * Does not flush the inserter.
   */
  private static ObjectId insertTree(ObjectInserter inserter, Path directory) throws IOException {
    checkArgument(Files.isDirectory(directory));

    /*
     * TODO TreeFormatter says that the entries must come in the <i>right</i> order; what’s that?
     */
    final TreeFormatter treeFormatter = new TreeFormatter();

    /* See TreeFormatter: “This formatter does not process subtrees”. */
    try (Stream<Path> content = Files.list(directory);) {
      for (Path relEntry : (Iterable<Path>) content::iterator) {
        final String entryName = relEntry.getFileName().toString();
        /* Work around Jimfs bug, see https://github.com/google/jimfs/issues/105 . */
        final Path entry = relEntry.toAbsolutePath();
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
          LOGGER.debug("Creating regular: {}.", entry);
          final String fileContent = Files.readString(entry);
          final ObjectId fileOid =
              inserter.insert(Constants.OBJ_BLOB, fileContent.getBytes(StandardCharsets.UTF_8));
          treeFormatter.append(entryName, FileMode.REGULAR_FILE, fileOid);
        } else if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
          final ObjectId tree = insertTree(inserter, entry);
          treeFormatter.append(entryName, FileMode.TREE, tree);
        } else if (Files.isSymbolicLink(entry)) {
          LOGGER.debug("Creating link: {}.", entry);
          final String destSlashSeparated;
          {
            final Path dest = Files.readSymbolicLink(entry);
            final String separator = dest.getFileSystem().getSeparator();
            if (dest.getFileSystem().provider().getScheme().equals("file")
                && separator.equals("\\")) {
              destSlashSeparated = dest.toString().replace("\\", "/");
            } else {
              checkArgument(separator.equals("/"));
              destSlashSeparated = dest.toString();
            }
          }
          final byte[] destAsBytes = destSlashSeparated.getBytes(StandardCharsets.UTF_8);
          final ObjectId fileOId = inserter.insert(Constants.OBJ_BLOB, destAsBytes);
          treeFormatter.append(entryName, FileMode.SYMLINK, fileOId);
        } else {
          throw new IllegalArgumentException("Unknown entry: " + entry);
        }
      }
    }

    final ObjectId inserted = inserter.insert(treeFormatter);
    return inserted;
  }

  public static void setMain(Repository repository, ObjectId newId) {
    try {
      final RefUpdate updateRef = repository.updateRef("refs/heads/main");
      updateRef.setNewObjectId(newId);
      final Result updateResult = updateRef.update();
      Verify.verify(updateResult == Result.NEW, updateResult.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    try {
      final RefUpdate updateRef = repository.updateRef(Constants.HEAD);
      final Result updateResult = updateRef.link("refs/heads/main");
      /*
       * Not sure this is best practice. But if HEAD is not set here, then log() commands fail on
       * repositories created with the above methods.
       */
      Verify.verify(updateResult == Result.FORCED, updateResult.toString());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
