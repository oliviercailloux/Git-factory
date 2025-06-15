package io.github.oliviercailloux.git.factory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

import com.google.common.base.Functions;
import com.google.common.base.Verify;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableGraph;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import io.github.oliviercailloux.git.common.IdStamp;
import io.github.oliviercailloux.jaris.graphs.GraphUtils;
import io.github.oliviercailloux.jaris.throwing.TOptional;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Graphs used here use the convention that the successor relation represents the child-of relation:
 * the successors of a node are its children; a pair (a, b) in the graph represents a parent commit
 * a and its child commit b. (See also gitjfs.)
 * <p>
 * TODO should be two, the simplest one not dealing with constant dags or closing dags, and which
 * also returns a mapping path ⇔ commits (is this useful?); and a wrapper one that admits constant
 * dags.
 */
public class FactoGit {
  @SuppressWarnings("unused")
  private static final Logger LOGGER = LoggerFactory.getLogger(FactoGit.class);

  @SuppressWarnings("unused")
  private static ImmutableSet<Path> toLineOwn(Graph<Path> lineGraph) {
    if (lineGraph.nodes().isEmpty()) {
      return ImmutableSet.of();
    }
    final ImmutableSet<Path> starters = lineGraph.nodes().stream()
        .filter(n -> lineGraph.predecessors(n).isEmpty()).collect(ImmutableSet.toImmutableSet());
    if (starters.isEmpty()) {
      verify(Graphs.hasCycle(lineGraph));
      throw new IllegalArgumentException("The given (supposedly 'line') graph has a cycle.");
    }
    if (starters.size() >= 2) {
      throw new IllegalArgumentException(
          "The given (supposedly 'line') graph has more than one starting point"
              + " (node with no parent).");
    }
    final Path starter = Iterables.getOnlyElement(starters);

    final ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    Path current = starter;
    do {
      builder.add(current);
      final Set<Path> nexts = lineGraph.successors(current);
      if (nexts.isEmpty()) {
        break;
      }
      if (nexts.size() >= 2) {
        throw new IllegalArgumentException(
            "The given (supposedly 'line') graph branches (some node has multiple children).");
      }
      current = Iterables.getOnlyElement(nexts);
    } while (true);

    final ImmutableSet<Path> line = builder.build();
    if (line.size() != lineGraph.nodes().size()) {
      /*
       * Only one starter but we did not exhaust the graph by following it; so some other component
       * of it cycles.
       */
      verify(Graphs.hasCycle(lineGraph));
      throw new IllegalArgumentException("The given (supposedly 'line') graph has a cycle.");
    }
    return line;
  }

  private static ImmutableSet<Path> toLine(Graph<Path> lineGraph) {
    checkArgument(lineGraph.nodes().stream().filter(n -> lineGraph.inDegree(n) == 0).count() == 1L);
    checkArgument(lineGraph.nodes().stream().allMatch(n -> lineGraph.inDegree(n) <= 1));
    checkArgument(
        lineGraph.nodes().stream().filter(n -> lineGraph.outDegree(n) == 0).count() == 1L);
    checkArgument(lineGraph.nodes().stream().allMatch(n -> lineGraph.outDegree(n) <= 1));
    verify(!Graphs.hasCycle(lineGraph));
    return GraphUtils.topologicallySortedNodes(lineGraph);
  }

  private static enum ConstantDag {
    BASIC, SUB, LINKED
  }

  private static class CloseableDagOfPaths implements Closeable {
    public static CloseableDagOfPaths given(Graph<Path> dag) {
      return new CloseableDagOfPaths(dag);
    }

    public static CloseableDagOfPaths dag(ConstantDag sort) throws IOException {
      switch (sort) {
        case BASIC:
          return dagBasic();
        case SUB:
          return dagSub();
        case LINKED:
          return dagLinked();
        default:
          throw new IllegalStateException();
      }
    }

    public static CloseableDagOfPaths dagBasic() throws IOException {
      final FileSystem jimFs = Jimfs.newFileSystem(Configuration.unix());
      return dagBasic(jimFs);
    }

    private static CloseableDagOfPaths dagBasic(FileSystem fs) throws IOException {
      final Path workDir = fs.getPath("");
      Files.writeString(workDir.resolve("file1.txt"), "Hello, world");
      Files.writeString(workDir.resolve("file2.txt"), "Hello again");
      final ImmutableGraph<Path> graph =
          GraphBuilder.directed().<Path>immutable().addNode(workDir).build();
      return new CloseableDagOfPaths(fs, graph);
    }

    public static CloseableDagOfPaths dagSub() throws IOException {
      final FileSystem jimFs = Jimfs.newFileSystem(Configuration.unix());
      return dagSub(jimFs);
    }

    private static CloseableDagOfPaths dagSub(FileSystem fs) throws IOException {
      final ImmutableList.Builder<Path> builder = ImmutableList.builder();

      {
        final Path workDir = fs.getPath("1");
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve("file1.txt"), "Hello, world");

        builder.add(workDir);
      }

      {
        final Path workDir = fs.getPath("2");
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve("file1.txt"), "Hello, world");
        Files.writeString(workDir.resolve("file2.txt"), "Hello again");

        builder.add(workDir);
      }

      {
        final Path workDir = fs.getPath("3");
        Files.createDirectories(workDir);
        Files.writeString(workDir.resolve("file1.txt"), "Hello, world");
        Files.writeString(workDir.resolve("file2.txt"), "I insist");
        final Path subDirectory = workDir.resolve("dir");
        Files.createDirectory(subDirectory);
        Files.writeString(subDirectory.resolve("file.txt"), "Hello from sub dir");

        builder.add(workDir);
      }

      final ImmutableGraph<Path> graph = ImmutableGraph.copyOf(GraphUtils.asGraph(builder.build()));
      return new CloseableDagOfPaths(fs, graph);
    }

    public static CloseableDagOfPaths dagLinked() throws IOException {
      final FileSystem jimFs = Jimfs.newFileSystem(Configuration.unix());
      return dagLinked(jimFs);
    }

    private static CloseableDagOfPaths dagLinked(FileSystem fs) throws IOException {
      final ImmutableList.Builder<Path> builder = ImmutableList.builder();

      {
        final Path workDir = fs.getPath("1");
        Files.createDirectories(workDir);
        final Path file1 = workDir.resolve("file1.txt");
        Files.writeString(file1, "Hello, world");
        final Path link = workDir.resolve("link.txt");
        Files.createSymbolicLink(link, file1);
        final Path absoluteLink = workDir.resolve("absolute link");
        Files.createSymbolicLink(absoluteLink, workDir.resolve("/absolute"));
        verify(Files.isSymbolicLink(link));

        builder.add(workDir);
      }

      {
        final Path workDir = fs.getPath("2");
        Files.createDirectories(workDir);
        final Path file1 = workDir.resolve("file1.txt");
        Files.writeString(file1, "Hello instead");
        final Path link = workDir.resolve("link.txt");
        Files.createSymbolicLink(link, file1);
        final Path absoluteLink = workDir.resolve("absolute link");
        Files.createSymbolicLink(absoluteLink, workDir.resolve("/absolute"));
        verify(Files.isSymbolicLink(link));

        builder.add(workDir);
      }

      {
        final Path workDir = fs.getPath("3");
        Files.createDirectories(workDir);
        final Path file1 = workDir.resolve("file1.txt");
        Files.writeString(file1, "Hello instead");
        final Path link = workDir.resolve("link.txt");
        Files.createSymbolicLink(link, file1);
        final Path absoluteLink = workDir.resolve("absolute link");
        Files.createSymbolicLink(absoluteLink, workDir.resolve("/absolute"));
        verify(Files.isSymbolicLink(link));
        final Path subDirectory = workDir.resolve("dir");
        Files.createDirectory(subDirectory);
        Files.createSymbolicLink(subDirectory.resolve("link"), fs.getPath("../link.txt"));
        Files.createSymbolicLink(subDirectory.resolve("linkToParent"), fs.getPath(".."));
        Files.createSymbolicLink(subDirectory.resolve("cyclingLink"),
            fs.getPath("../dir/cyclingLink"));

        builder.add(workDir);
      }

      {
        final Path workDir = fs.getPath("4");
        Files.createDirectories(workDir);
        final Path file1 = workDir.resolve("file1.txt");
        Files.writeString(file1, "Hello instead");
        final Path link = workDir.resolve("link.txt");
        Files.createSymbolicLink(link, file1);
        final Path absoluteLink = workDir.resolve("absolute link");
        Files.createSymbolicLink(absoluteLink, workDir.resolve("/absolute"));
        verify(Files.isSymbolicLink(link));
        final Path subDirectory = workDir.resolve("dir");
        Files.createDirectory(subDirectory);
        Files.createSymbolicLink(subDirectory.resolve("link"), fs.getPath("../link.txt"));
        Files.createSymbolicLink(subDirectory.resolve("linkToParent"), fs.getPath(".."));
        Files.createSymbolicLink(subDirectory.resolve("cyclingLink"),
            fs.getPath("../dir/cyclingLink"));
        Files.delete(file1);

        builder.add(workDir);
      }

      final ImmutableGraph<Path> graph = ImmutableGraph.copyOf(GraphUtils.asGraph(builder.build()));
      return new CloseableDagOfPaths(fs, graph);
    }

    private final TOptional<FileSystem> fs;
    private final ImmutableGraph<Path> dag;

    private CloseableDagOfPaths(FileSystem fs, Graph<Path> dag) {
      this.fs = TOptional.of(fs);
      this.dag = ImmutableGraph.copyOf(dag);
      verify(!Graphs.hasCycle(dag));
    }

    private CloseableDagOfPaths(Graph<Path> dag) {
      this.fs = TOptional.empty();
      this.dag = ImmutableGraph.copyOf(dag);
      verify(!Graphs.hasCycle(dag));
    }

    @SuppressWarnings("OverloadMethodsDeclarationOrder")
    public ImmutableGraph<Path> dag() {
      return dag;
    }

    @Override
    public void close() throws IOException {
      fs.ifPresent(f -> f.close());
    }
  }

  public static FactoGit empty() {
    return new FactoGit();
  }

  public static void clearConfig() {
    SystemReader.setInstance(new EmptyConfigSystemReader());
  }
  
  private static Function<Path, IdStamp> identsFunction(ImmutableGraph<Path> ourDag,
      IdStamp identStartThenIncrease) {
    final Function<Path, IdStamp> ourIdent;
    final ImmutableSet<Path> line = toLine(ourDag);
    final ImmutableMap.Builder<Path, IdStamp> builder = ImmutableMap.builder();
    IdStamp current = identStartThenIncrease;
    for (Path path : line) {
      builder.put(path, current);
      current = new IdStamp(current.name(), current.email(),
          current.timestamp().plus(1, ChronoUnit.HOURS));
    }
    final ImmutableMap<Path, IdStamp> idents = builder.build();
    ourIdent = Functions.forMap(idents);
    return ourIdent;
  }

  private static Function<Path, String> messagesFunction(ImmutableGraph<Path> ourDag) {
    final ImmutableSet<Path> line = toLine(ourDag);
    final ImmutableMap.Builder<Path, String> builder = ImmutableMap.builder();
    int current = 1;
    for (Path path : line) {
      builder.put(path, "Commit number " + current);
      ++current;
    }
    final ImmutableMap<Path, String> idents = builder.build();
    return Functions.forMap(idents);
  }

  private static PersonIdent personIdent(IdStamp ident) {
    return new PersonIdent(ident.name(), ident.email(), ident.timestamp().toInstant(),
        ident.timestamp().getZone());
  }

  private String name;

  /**
   * Does not return <code>null</code>. Exactly one of ident and identStartThenIncrease is null.
   */
  private Function<Path, IdStamp> ident;
  /**
   * Exactly one of ident and identStartThenIncrease is null.
   */
  private IdStamp identStartThenIncrease;
  /**
   * No cycle. At least one of dag and constantDag is {@code null}.
   */
  private ImmutableGraph<Path> dag;
  /**
   * At least one of dag and constantDag is {@code null}.
   */
  private ConstantDag constantDag;
  private Path links;

  private FactoGit() {
    name = "";
    ident = p -> new IdStamp("", "", Instant.now().atZone(ZoneOffset.UTC));
    dag = null;
    constantDag = null;
    links = null;
    identStartThenIncrease = null;
  }

  public void setIdentConstant(IdStamp ident) {
    checkNotNull(ident);
    this.ident = p -> ident;
    identStartThenIncrease = null;
  }

  public void setIdentIncreasing(IdStamp start) {
    ident = null;
    identStartThenIncrease = checkNotNull(start);
  }

  /**
   * @param identF use Functions.forMap(idents) if you have a map.
   */
  public void setIdentFunction(Function<Path, IdStamp> identF) {
    this.ident = identF.andThen(i -> {
      if (i == null) {
        throw new IllegalArgumentException();
      }
      return i;
    });
    identStartThenIncrease = null;
  }

  public void setDag(Graph<Path> dag) {
    checkArgument(!Graphs.hasCycle(dag));
    this.dag = ImmutableGraph.copyOf(dag);
    constantDag = null;
  }

  public void setSingletonDag(Path root) {
    dag = GraphBuilder.directed().<Path>immutable().addNode(root).build();
    constantDag = null;
  }

  public void setBasicDag() {
    dag = null;
    constantDag = ConstantDag.BASIC;
  }

  public void setSubDag() {
    dag = null;
    constantDag = ConstantDag.SUB;
  }

  public void setLinkedDag() {
    dag = null;
    constantDag = ConstantDag.LINKED;
  }

  public void setLinks(Path links) {
    this.links = links;
  }

  public void setName(String name) {
    this.name = checkNotNull(name);
  }

  public InMemoryRepository build() throws IOException {
    verify(dag == null || constantDag == null);
    verify(Objects.isNull(ident) != Objects.isNull(identStartThenIncrease));
    checkState(dag != null || constantDag != null);

    final InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription(name));
    repository.create(true);
    {
      final ImmutableList<Ref> refs = ImmutableList.copyOf(repository.getRefDatabase().getRefs());
      verify(refs.size() == 0, refs.toString());
    }
    {
      final ImmutableList<Ref> refs =
          ImmutableList.copyOf(repository.getRefDatabase().getAdditionalRefs());
      verify(refs.size() == 0, refs.toString());
    }
    final ObjectDatabase objectDatabase = repository.getObjectDatabase();

    try (CloseableDagOfPaths clDag = TOptional.ofNullable(constantDag)
        .map(c -> CloseableDagOfPaths.dag(c)).orElseGet(() -> CloseableDagOfPaths.given(dag))) {
      final ImmutableGraph<Path> ourDag = clDag.dag();

      final Function<Path, IdStamp> ourIdent = Optional.ofNullable(ident)
          .orElseGet(() -> identsFunction(ourDag, identStartThenIncrease));
      final Function<Path, String> messagesFunction = messagesFunction(ourDag);

      final ImmutableSet<Path> starters = ourDag.nodes().stream()
          .filter(p -> ourDag.predecessors(p).size() == 0).collect(ImmutableSet.toImmutableSet());
      LOGGER.debug("Visiting from {}.", starters);
      final ImmutableSet<Path> sources = GraphUtils.topologicallySortedNodes(ourDag);

      final BiMap<Path, ObjectId> commitsBuilder = HashBiMap.create(ourDag.nodes().size());
      try (ObjectInserter inserter = objectDatabase.newInserter()) {
        for (Path source : sources) {
          LOGGER.debug("Visiting {}.", source);
          final Set<Path> parentPaths = ourDag.predecessors(source);
          final ImmutableList<ObjectId> parents = parentPaths.stream()
              .map(p -> commitsBuilder.get(p)).collect(ImmutableSet.toImmutableSet()).asList();
          final ObjectId oId = insertCommit(inserter, personIdent(ourIdent.apply(source)), source,
              parents, messagesFunction.apply(source));
          commitsBuilder.put(source, oId);
        }
      }
      final ImmutableBiMap<Path, ObjectId> commits = ImmutableBiMap.copyOf(commitsBuilder);
      final ImmutableSet<Path> ends = ourDag.nodes().stream().filter(n -> ourDag.outDegree(n) == 0)
          .collect(ImmutableSet.toImmutableSet());
      if (ends.size() == 1) {
        final Path lastVisited = sources.asList().get(sources.size() - 1);
        verify(ourDag.outDegree(lastVisited) == 0);
        final ObjectId lastCommit = commits.get(lastVisited);
        setMainAndHead(repository, lastCommit);
        LOGGER.debug("Set main at {}.", lastCommit);
      } else {
        LOGGER.debug("Set no main.");
      }

      final ImmutableSet<Path> allLinks;
      if (links != null) {
        allLinks = Files.find(links, 50, (p, a) -> true).filter(p -> !Files.isDirectory(p))
            .collect(ImmutableSet.toImmutableSet());
      } else {
        allLinks = ImmutableSet.of();
      }
      for (Path link : allLinks) {
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
        // Git.wrap(repository).branchCreate().setName("origin/" +
        // link.getFileName().toString())
        // .setStartPoint(targetId.getName()).call();
      }
    }

    return repository;
  }

  public static InMemoryRepository createBasicRepo() throws IOException {
    final FactoGit f = FactoGit.empty();
    f.setBasicDag();
    return f.build();
  }

  public static InMemoryRepository createRepository(IdStamp ident, Graph<Path> baseDirs, Path links)
      throws IOException {
    final FactoGit f = FactoGit.empty();
    f.setIdentConstant(ident);
    f.setDag(baseDirs);
    f.setLinks(links);
    return f.build();
  }

  public static InMemoryRepository createRepository(IdStamp ident, String path, String content)
      throws IOException {
    try (FileSystem jimFs = Jimfs.newFileSystem(Configuration.unix())) {
      final Path workDir = jimFs.getPath("");

      final Path target = workDir.resolve(path);
      Files.createDirectories(target.getParent());
      Files.writeString(target, content);

      final FactoGit f = FactoGit.empty();
      f.setIdentConstant(ident);
      f.setSingletonDag(target);
      return f.build();
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
          final ObjectId fileObjId = inserter.insert(Constants.OBJ_BLOB, destAsBytes);
          treeFormatter.append(entryName, FileMode.SYMLINK, fileObjId);
        } else {
          throw new IllegalArgumentException("Unknown entry: " + entry);
        }
      }
    }

    final ObjectId inserted = inserter.insert(treeFormatter);
    return inserted;
  }

  private static void setMainAndHead(Repository repository, ObjectId newId) throws IOException {
    {
      final RefUpdate updateRef = repository.updateRef("refs/heads/main");
      updateRef.setNewObjectId(newId);
      final Result updateResult = updateRef.update();
      Verify.verify(updateResult == Result.NEW, updateResult.toString());
    }
    {
      final ImmutableList<Ref> refs = ImmutableList.copyOf(repository.getRefDatabase().getRefs());
      verify(refs.size() == 1, refs.toString());
    }
    {
      final RefUpdate updateRef = repository.updateRef(Constants.HEAD);
      final Result updateResult = updateRef.link("refs/heads/main");
      verify(updateResult == Result.FORCED, updateResult.toString());
      {
        final ImmutableList<Ref> refs = ImmutableList.copyOf(repository.getRefDatabase().getRefs());
        verify(refs.size() == 2, refs.toString());
      }
    }
  }
}
