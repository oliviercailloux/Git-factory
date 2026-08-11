package io.github.oliviercailloux.git.factory;

import static com.google.common.base.Verify.verify;

import com.google.common.io.ByteSource;
import com.google.common.io.CharSource;
import com.google.common.io.Resources;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;

public class FastImporter {
  /**
   * Whether the given top-level line is a fast-import command this importer deliberately treats
   * as a no-op, because it carries no information relevant to the resulting repository content:
   * a stream comment, or one of {@code checkpoint}/{@code progress}/{@code done}/{@code feature}/
   * {@code option}.
   */
  private static boolean isHarmlessTopLevelCommand(String line) {
    return line.startsWith("#") || line.equals("checkpoint") || line.startsWith("progress ")
        || line.equals("done") || line.startsWith("feature ") || line.startsWith("option ");
  }

  public static DfsRepository importRepository(ByteSource source) throws IOException {
    final InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription(""));
    repository.create(true);

    final MarkRegistry registry = MarkRegistry.create();

    try (InputStream stream = new BufferedInputStream(source.openStream());
        ObjectInserter inserter = repository.getObjectDatabase().newInserter()) {
      String line = MarkRegistry.readLine(stream);
      while (line != null) {
        if (line.isEmpty()) {
          line = MarkRegistry.readLine(stream);
          continue;
        }
        if (line.equals("blob")) {
          registry.readBlob(stream, inserter);
          line = MarkRegistry.readLine(stream);
        } else if (line.startsWith("reset ")) {
          line = registry.readReset(stream, repository, line.substring("reset ".length()));
        } else if (line.startsWith("commit ")) {
          registry.readCommit(stream, inserter, repository, line.substring("commit ".length()));
          line = MarkRegistry.readLine(stream);
        } else if (line.startsWith("tag ")) {
          registry.readTag(stream, inserter, repository, line.substring("tag ".length()));
          line = MarkRegistry.readLine(stream);
        } else if (isHarmlessTopLevelCommand(line)) {
          line = MarkRegistry.readLine(stream);
        } else {
          throw new IllegalStateException("Unsupported fast-import command: " + line);
        }
      }
    }

    return repository;
  }

  public static DfsRepository importRepository(CharSource source) throws IOException {
    return importRepository(ByteSource.wrap(source.read().getBytes(StandardCharsets.UTF_8)));
  }

  private static ByteSource bundled(String resourceName) {
    return Resources.asByteSource(Resources.getResource(FastImporter.class, resourceName));
  }

  /**
   * A single commit with two sibling files ({@code file1.txt}, {@code file2.txt}). Equivalent
   * topology to the deprecated {@code FactoGit#setBasicDag()}.
   */
  public static DfsRepository single() throws IOException {
    return importRepository(bundled("single.fast-export"));
  }

  /**
   * A 2-commit line: the first commit has a single file ({@code file1.txt}), the second adds a
   * sibling ({@code file2.txt}). Equivalent topology to the deprecated
   * {@code JGit#createBasicRepo(Repository)}.
   */
  public static DfsRepository dual() throws IOException {
    return importRepository(bundled("dual.fast-export"));
  }

  /**
   * A 3-commit line, growing the tree at each step and ending with a subdirectory
   * ({@code dir/file.txt}). Equivalent topology to the deprecated {@code FactoGit#setSubDag()}.
   */
  public static DfsRepository sub() throws IOException {
    return importRepository(bundled("sub.fast-export"));
  }

  /**
   * A 4-commit line exercising symlinks: relative, absolute (dangling by construction), a
   * subdirectory holding a link into its parent and a self-cycling link, and finally a dangling
   * relative link after its target file is removed. Equivalent topology to the deprecated
   * {@code FactoGit#setLinkedDag()}.
   */
  public static DfsRepository linked() throws IOException {
    return importRepository(bundled("linked.fast-export"));
  }

  private static sealed interface TreeNode permits FileNode, DirNode {}

  private static record FileNode(MEntry entry) implements TreeNode {}

  private static record DirNode(LinkedHashMap<String, TreeNode> children) implements TreeNode {}

  private static DirNode putEntry(DirNode dir, String path, MEntry entry) {
    final LinkedHashMap<String, TreeNode> newChildren = new LinkedHashMap<>(dir.children());
    final int slash = path.indexOf('/');
    if (slash < 0) {
      newChildren.put(path, new FileNode(entry));
    } else {
      final String dirName = path.substring(0, slash);
      final DirNode subdir = newChildren.containsKey(dirName)
          ? (DirNode) newChildren.get(dirName)
          : new DirNode(new LinkedHashMap<>());
      newChildren.put(dirName, putEntry(subdir, path.substring(slash + 1), entry));
    }
    return new DirNode(newChildren);
  }

  /**
   * Directories sort as if their name had a trailing {@code /} appended (byte value 0x2F), which
   * places them after sibling entries whose names start with the same prefix followed by any byte
   * {@literal <} 0x2F (e.g. {@code '-'} = 0x2D). This matches the order git expects inside a tree
   * object.
   */
  private static final Comparator<Map.Entry<String, TreeNode>> GIT_TREE_ORDER =
      Comparator.comparing(entry -> entry.getValue() instanceof DirNode
          ? entry.getKey() + "/"
          : entry.getKey());

  private static ObjectId insertDirNode(ObjectInserter inserter, DirNode dir) throws IOException {
    final TreeFormatter formatter = new TreeFormatter();
    final List<Map.Entry<String, TreeNode>> sorted =
        dir.children().entrySet().stream().sorted(GIT_TREE_ORDER).toList();
    for (Map.Entry<String, TreeNode> entry : sorted) {
      switch (entry.getValue()) {
        case FileNode fn -> formatter.append(entry.getKey(), fn.entry().mode(), fn.entry().oid());
        case DirNode dn ->
            formatter.append(entry.getKey(), FileMode.TREE, insertDirNode(inserter, dn));
      }
    }
    return inserter.insert(formatter);
  }

  static ObjectId insertTree(ObjectInserter inserter, Map<String, MEntry> files)
      throws IOException {
    DirNode root = new DirNode(new LinkedHashMap<>());
    for (Map.Entry<String, MEntry> entry : files.entrySet()) {
      root = putEntry(root, entry.getKey(), entry.getValue());
    }
    return insertDirNode(inserter, root);
  }

  static void setRef(Repository repository, String ref, ObjectId newId) throws IOException {
    final RefUpdate updateRef = repository.updateRef(ref);
    updateRef.setNewObjectId(newId);
    final Result result = updateRef.update();
    verify(result == Result.NEW || result == Result.FAST_FORWARD, result.toString());
    if (ref.equals("refs/heads/main")) {
      final RefUpdate headUpdate = repository.updateRef(Constants.HEAD);
      final Result headResult = headUpdate.link("refs/heads/main");
      verify(headResult == Result.FORCED || headResult == Result.NO_CHANGE, headResult.toString());
    }
  }

  private FastImporter() {}
}
