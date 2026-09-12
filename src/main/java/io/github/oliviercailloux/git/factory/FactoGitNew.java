package io.github.oliviercailloux.git.factory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Verify.verify;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Graph;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.Graphs;
import com.google.common.graph.ImmutableGraph;
import com.google.common.graph.MutableGraph;
import com.google.common.graph.Traverser;
import io.github.oliviercailloux.git.common.IdStamp;
import io.github.oliviercailloux.jaris.graphs.GraphUtils;
import io.github.oliviercailloux.jaris.throwing.TFunction;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.BaseRepositoryBuilder;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable builder for in-memory JGit repositories.
 *
 * <p>
 * Use {@link #withRoot} and {@link #withChild} to construct the DAG incrementally. Each
 * {@code with…} method returns a new instance; the receiver is never modified.
 *
 * <p>
 * Call {@link #repo()} to build a fresh {@link DfsRepository}; it is safe to call multiple times on
 * the same instance.
 */
public class FactoGitNew {
  private static final Logger LOGGER = LoggerFactory.getLogger(FactoGitNew.class);

  private static final IdStamp DEFAULT_IDENT =
      new IdStamp("", "", Instant.EPOCH.atZone(ZoneOffset.UTC));

  private static final OnDemandFunctionOfPath<IdStamp> DEFAULT_COMMITTERS =
      OnDemandFunctionOfPath.function(p -> DEFAULT_IDENT);

  private static final OnDemandFunctionOfPath<String> DEFAULT_MESSAGES =
      OnDemandFunctionOfPath.onBreadthFirst(
          () -> IntStream.iterate(1, i -> i + 1).mapToObj(i -> "Commit number " + i).iterator());

  private static String defaultName() {
    return "factogit-created on " + Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * Returns an instance with an empty DAG and all defaults: epoch committer, auto-numbered
   * messages, and a name of the form {@code "factogit created on <ISO timestamp>"}.
   */
  public static FactoGitNew empty() {
    return new FactoGitNew(defaultName(), GraphBuilder.directed().<Path>immutable().build(),
        DEFAULT_COMMITTERS, DEFAULT_MESSAGES);
  }

  /**
   * Returns an instance with the given DAG and all other fields set to defaults.
   *
   * @throws IllegalArgumentException if {@code dag} contains a cycle
   */
  public static FactoGitNew ofDag(Graph<Path> dag) {
    checkArgument(!Graphs.hasCycle(dag));
    return new FactoGitNew(defaultName(), ImmutableGraph.copyOf(dag), DEFAULT_COMMITTERS,
        DEFAULT_MESSAGES);
  }

  private static MutableGraph<Path> mutableCopy(Graph<Path> source) {
    MutableGraph<Path> copy = GraphBuilder.from(source).build();
    source.nodes().forEach(copy::addNode);
    source.edges().forEach(e -> copy.putEdge(e.nodeU(), e.nodeV()));
    return copy;
  }

  private static PersonIdent personIdent(IdStamp ident) {
    return new PersonIdent(ident.name(), ident.email(), ident.timestamp().toInstant(),
        ident.timestamp().getZone());
  }

  /**
   * Directories sort as if their name had a trailing {@code /} appended (byte value 0x2F), which
   * places them after files whose names start with the same prefix followed by any byte
   * {@literal <} 0x2F (e.g. {@code '.'} = 0x2E). This matches the order git expects inside a tree
   * object.
   */
  private static final Comparator<Path> GIT_TREE_ORDER = (p1, p2) -> {
    String n1 = p1.getFileName().toString();
    String n2 = p2.getFileName().toString();
    String k1 = Files.isDirectory(p1, LinkOption.NOFOLLOW_LINKS) ? n1 + "/" : n1;
    String k2 = Files.isDirectory(p2, LinkOption.NOFOLLOW_LINKS) ? n2 + "/" : n2;
    return k1.compareTo(k2);
  };

  private static ObjectId insertTree(ObjectInserter inserter, Path directory) throws IOException {
    checkArgument(Files.isDirectory(directory));
    TreeFormatter treeFormatter = new TreeFormatter();
    try (Stream<Path> content = Files.list(directory).sorted(GIT_TREE_ORDER)) {
      for (Path relEntry : (Iterable<Path>) content::iterator) {
        String entryName = relEntry.getFileName().toString();
        Path entry = relEntry.toAbsolutePath();
        if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
          String fileContent = Files.readString(entry);
          ObjectId fileOid =
              inserter.insert(Constants.OBJ_BLOB, fileContent.getBytes(StandardCharsets.UTF_8));
          treeFormatter.append(entryName, FileMode.REGULAR_FILE, fileOid);
        } else if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
          treeFormatter.append(entryName, FileMode.TREE, insertTree(inserter, entry));
        } else if (Files.isSymbolicLink(entry)) {
          Path dest = Files.readSymbolicLink(entry);
          String separator = dest.getFileSystem().getSeparator();
          String destSlashSeparated;
          if (dest.getFileSystem().provider().getScheme().equals("file")
              && separator.equals("\\")) {
            destSlashSeparated = dest.toString().replace("\\", "/");
          } else {
            checkArgument(separator.equals("/"));
            destSlashSeparated = dest.toString();
          }
          ObjectId fileObjId = inserter.insert(Constants.OBJ_BLOB,
              destSlashSeparated.getBytes(StandardCharsets.UTF_8));
          treeFormatter.append(entryName, FileMode.SYMLINK, fileObjId);
        } else {
          throw new IllegalArgumentException("Unknown entry: " + entry);
        }
      }
    }
    return inserter.insert(treeFormatter);
  }

  /** Builds and inserts one commit object. Does not flush; the caller is responsible. */
  static ObjectId insertCommit(ObjectInserter inserter, PersonIdent author, PersonIdent committer,
      ObjectId treeId, List<ObjectId> parents, String message) throws IOException {
    CommitBuilder commitBuilder = new CommitBuilder();
    commitBuilder.setMessage(message);
    commitBuilder.setAuthor(author);
    commitBuilder.setCommitter(committer);
    commitBuilder.setTreeId(treeId);
    for (ObjectId parent : parents) {
      commitBuilder.addParentId(parent);
    }
    return inserter.insert(commitBuilder);
  }

  private static void setMainAndHead(Repository repository, ObjectId newId) throws IOException {
    {
      RefUpdate updateRef = repository.updateRef("refs/heads/main");
      updateRef.setNewObjectId(newId);
      Result updateResult = updateRef.update();
      verify(updateResult == Result.NEW, updateResult.toString());
    }
    {
      ImmutableList<Ref> refs = ImmutableList.copyOf(repository.getRefDatabase().getRefs());
      verify(refs.size() == 1, refs.toString());
    }
    {
      RefUpdate updateRef = repository.updateRef(Constants.HEAD);
      Result updateResult = updateRef.link("refs/heads/main");
      verify(updateResult == Result.FORCED, updateResult.toString());
      {
        ImmutableList<Ref> refs = ImmutableList.copyOf(repository.getRefDatabase().getRefs());
        verify(refs.size() == 2, refs.toString());
      }
    }
  }

  private final String name;
  private final ImmutableGraph<Path> dag;
  private final OnDemandFunctionOfPath<IdStamp> committers;
  private final OnDemandFunctionOfPath<String> commitMessages;

  private FactoGitNew(String name, ImmutableGraph<Path> dag,
      OnDemandFunctionOfPath<IdStamp> committers, OnDemandFunctionOfPath<String> commitMessages) {
    this.name = checkNotNull(name);
    this.dag = checkNotNull(dag);
    this.committers = checkNotNull(committers);
    this.commitMessages = checkNotNull(commitMessages);
  }

  public FactoGitNew named(String newName) {
    return new FactoGitNew(checkNotNull(newName), dag, committers, commitMessages);
  }

  /**
   * Returns an instance using {@code f} to assign an {@link IdStamp} to each path.
   */
  public FactoGitNew withCommitters(TFunction<Path, IdStamp, IOException> f) {
    return new FactoGitNew(name, dag, OnDemandFunctionOfPath.function(f), commitMessages);
  }

  /**
   * Returns an instance assigning stamps from {@code values} in BFS order over the DAG.
   */
  public FactoGitNew withCommitters(Iterable<IdStamp> values) {
    return new FactoGitNew(name, dag, OnDemandFunctionOfPath.onBreadthFirst(values),
        commitMessages);
  }

  /**
   * Returns an instance using {@code f} to assign a commit message to each path.
   */
  public FactoGitNew withMessages(TFunction<Path, String, IOException> f) {
    return new FactoGitNew(name, dag, committers, OnDemandFunctionOfPath.function(f));
  }

  /**
   * Returns an instance assigning messages from {@code values} in BFS order over the DAG.
   */
  public FactoGitNew withMessages(Iterable<String> values) {
    return new FactoGitNew(name, dag, committers, OnDemandFunctionOfPath.onBreadthFirst(values));
  }

  /**
   * Returns an instance whose DAG additionally contains {@code root} as an isolated node.
   */
  public FactoGitNew withRoot(Path root) {
    MutableGraph<Path> copy = mutableCopy(dag);
    copy.addNode(root);
    return new FactoGitNew(name, ImmutableGraph.copyOf(copy), committers, commitMessages);
  }

  /**
   * Returns an instance whose DAG additionally contains the edge {@code parent → child}. Both
   * endpoints are added to the DAG if not already present.
   *
   * @throws IllegalArgumentException if adding the edge would create a cycle
   */
  public FactoGitNew withChild(Path parent, Path child) {
    MutableGraph<Path> copy = mutableCopy(dag);
    copy.putEdge(parent, child);
    checkArgument(!Graphs.hasCycle(copy));
    return new FactoGitNew(name, ImmutableGraph.copyOf(copy), committers, commitMessages);
  }

  /**
   * Returns the DAG.
   */
  public ImmutableGraph<Path> dag() {
    return dag;
  }

  /**
   * Builds and returns a fresh repository created by {@code builder}. Safe to call multiple times.
   *
   * <p>
   * Commits are inserted in topological order. {@code refs/heads/main} and {@code HEAD} are set to
   * the last node visited in breadth-first order from all roots.
   */
  public <R extends Repository> R repo(BaseRepositoryBuilder<?, R> builder) throws IOException {
    TFunction<Path, IdStamp, IOException> ourCommitters = committers.summon(dag);
    TFunction<Path, String, IOException> ourMessages = commitMessages.summon(dag);

    R repository = builder.build();
    repository.create(true);

    ImmutableSet<Path> topoOrder = GraphUtils.topologicallySortedNodes(dag);
    BiMap<Path, ObjectId> commitsMap = HashBiMap.create(dag.nodes().size());

    try (ObjectInserter inserter = repository.getObjectDatabase().newInserter()) {
      for (Path source : topoOrder) {
        ImmutableList<ObjectId> parents = dag.predecessors(source).stream().map(commitsMap::get)
            .collect(ImmutableList.toImmutableList());
        PersonIdent ident = personIdent(ourCommitters.apply(source));
        ObjectId oId = insertCommit(inserter, ident, ident, insertTree(inserter, source), parents,
            ourMessages.apply(source));
        commitsMap.put(source, oId);
        LOGGER.debug("Created commit for {}: {}.", source, oId);
      }
      inserter.flush();
    }
    ImmutableBiMap<Path, ObjectId> commits = ImmutableBiMap.copyOf(commitsMap);

    if (!dag.nodes().isEmpty()) {
      ImmutableSet<Path> roots = dag.nodes().stream().filter(n -> dag.inDegree(n) == 0)
          .collect(ImmutableSet.toImmutableSet());
      Path bfsLast = null;
      for (Path node : Traverser.<Path>forGraph(dag::successors).breadthFirst(roots)) {
        bfsLast = node;
      }
      setMainAndHead(repository, commits.get(bfsLast));
      LOGGER.debug("Set main and HEAD at BFS-last node {}.", bfsLast);
    }

    return repository;
  }

  /** Builds and returns a fresh {@link DfsRepository}. */
  public DfsRepository repo() throws IOException {
    return repo(new InMemoryRepository.Builder()
        .setRepositoryDescription(new DfsRepositoryDescription(name)));
  }
}
