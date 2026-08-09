package io.github.oliviercailloux.git.factory;

import static com.google.common.base.Verify.verify;

import com.google.common.io.ByteSource;
import com.google.common.io.CharSource;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
  private FastImporter() {}

  public static DfsRepository importRepository(CharSource source) throws IOException {
    return importRepository(ByteSource.wrap(source.read().getBytes(StandardCharsets.UTF_8)));
  }

  public static DfsRepository importRepository(ByteSource source) throws IOException {
    final InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription(""));
    repository.create(true);

    final MarkRegistry registry = new MarkRegistry();

    try (InputStream stream = new BufferedInputStream(source.openStream());
        ObjectInserter inserter = repository.getObjectDatabase().newInserter()) {
      String line;
      while ((line = MarkRegistry.readLine(stream)) != null) {
        if (line.isEmpty()) {
          continue;
        }
        if (line.equals("blob")) {
          registry.readBlob(stream, inserter);
        } else if (line.startsWith("reset ")) {
          // skip
        } else if (line.startsWith("commit ")) {
          registry.readCommit(stream, inserter, repository, line.substring("commit ".length()));
        } else if (line.startsWith("tag ")) {
          registry.readTag(stream, inserter, repository, line.substring("tag ".length()));
        }
      }
    }

    return repository;
  }

  private static sealed interface TreeNode permits FileNode, DirNode {}

  private static record FileNode(MEntry entry) implements TreeNode {}

  private static record DirNode(LinkedHashMap<String, TreeNode> children) implements TreeNode {}

  static ObjectId insertTree(ObjectInserter inserter, Map<String, MEntry> files)
      throws IOException {
    DirNode root = new DirNode(new LinkedHashMap<>());
    for (Map.Entry<String, MEntry> entry : files.entrySet()) {
      root = putEntry(root, entry.getKey(), entry.getValue());
    }
    return insertDirNode(inserter, root);
  }

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

  private static ObjectId insertDirNode(ObjectInserter inserter, DirNode dir) throws IOException {
    final TreeFormatter formatter = new TreeFormatter();
    for (Map.Entry<String, TreeNode> entry : dir.children().entrySet()) {
      switch (entry.getValue()) {
        case FileNode fn -> formatter.append(entry.getKey(), fn.entry().mode(), fn.entry().oid());
        case DirNode dn ->
            formatter.append(entry.getKey(), FileMode.TREE, insertDirNode(inserter, dn));
      }
    }
    return inserter.insert(formatter);
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
}
