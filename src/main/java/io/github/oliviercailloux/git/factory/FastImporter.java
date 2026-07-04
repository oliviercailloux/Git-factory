package io.github.oliviercailloux.git.factory;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

import com.google.common.io.ByteSource;
import com.google.common.io.CharSource;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FastImporter {
  private static record MEntry(FileMode mode, ObjectId oid) {}

  @SuppressWarnings("unused")
  private static final Logger LOGGER = LoggerFactory.getLogger(FastImporter.class);

  private FastImporter() {}

  public static DfsRepository importRepository(CharSource source) throws IOException {
    return importRepository(ByteSource.wrap(source.read().getBytes(StandardCharsets.UTF_8)));
  }

  public static DfsRepository importRepository(ByteSource source) throws IOException {
    final InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription(""));
    repository.create(true);

    final Map<Integer, ObjectId> marks = new HashMap<>();
    final Map<Integer, Map<String, MEntry>> fileMaps = new HashMap<>();

    try (InputStream stream = new BufferedInputStream(source.openStream());
        ObjectInserter inserter = repository.getObjectDatabase().newInserter()) {
      String line;
      while ((line = readLine(stream)) != null) {
        if (line.isEmpty()) {
          continue;
        }
        if (line.equals("blob")) {
          readBlob(stream, inserter, marks);
        } else if (line.startsWith("reset ")) {
          // skip
        } else if (line.startsWith("commit ")) {
          final String ref = line.substring("commit ".length());
          readCommit(stream, inserter, marks, fileMaps, repository, ref);
        } else if (line.startsWith("tag ")) {
          final String tagName = line.substring("tag ".length());
          readTag(stream, inserter, marks, repository, tagName);
        }
      }
    }

    return repository;
  }

  private static void readBlob(InputStream stream, ObjectInserter inserter,
      Map<Integer, ObjectId> marks) throws IOException {
    final int mark = readMark(stream);
    final int length = readDataLength(stream);
    final byte[] content = readExactlyBytes(stream, length);
    final ObjectId oid = inserter.insert(Constants.OBJ_BLOB, content);
    marks.put(mark, oid);
    LOGGER.debug("Inserted blob mark :{} → {}.", mark, oid);
  }

  private static void readCommit(InputStream stream, ObjectInserter inserter,
      Map<Integer, ObjectId> marks, Map<Integer, Map<String, MEntry>> fileMaps,
      InMemoryRepository repository, String ref) throws IOException {
    final int mark = readMark(stream);
    final PersonIdent author = readIdent(stream, "author");
    final PersonIdent committer = readIdent(stream, "committer");
    String line = readLine(stream);
    if (line != null && line.startsWith("encoding ")) {
      line = readLine(stream);
    }
    checkState(line != null && line.startsWith("data "), "Expected data line, got: %s", line);
    final int msgLength = Integer.parseInt(line.substring("data ".length()));
    final String message = new String(readExactlyBytes(stream, msgLength), StandardCharsets.UTF_8);

    line = readLine(stream);
    final List<ObjectId> parents = new ArrayList<>();
    Integer firstParentMark = null;
    if (line != null && line.startsWith("from :")) {
      firstParentMark = Integer.parseInt(line.substring("from :".length()));
      parents.add(marks.get(firstParentMark));
      line = readLine(stream);
    }
    while (line != null && line.startsWith("merge :")) {
      parents.add(marks.get(Integer.parseInt(line.substring("merge :".length()))));
      line = readLine(stream);
    }

    final Map<String, MEntry> files = new LinkedHashMap<>();
    if ("deleteall".equals(line)) {
      line = readLine(stream);
    } else {
      if (firstParentMark != null) {
        final Map<String, MEntry> parentFiles = fileMaps.get(firstParentMark);
        if (parentFiles != null) {
          files.putAll(parentFiles);
        }
      }
    }

    while (line != null && !line.isEmpty()) {
      if (line.startsWith("M ")) {
        final String[] parts = line.split(" ", 4);
        final FileMode mode = FileMode.fromBits(Integer.parseInt(parts[1], 8));
        final ObjectId oid = parts[2].startsWith(":")
            ? marks.get(Integer.parseInt(parts[2].substring(1)))
            : ObjectId.fromString(parts[2]);
        files.put(parts[3], new MEntry(mode, oid));
      } else if (line.startsWith("D ")) {
        files.remove(line.substring(2));
      } else if (line.startsWith("R ")) {
        final String[] parts = line.split(" ", 3);
        files.put(parts[2], files.remove(parts[1]));
      } else if (line.startsWith("C ")) {
        final String[] parts = line.split(" ", 3);
        files.put(parts[2], files.get(parts[1]));
      }
      line = readLine(stream);
    }

    final ObjectId treeId = insertTree(inserter, files);
    final ObjectId commitId = FactoGitNew.insertCommit(inserter, author, committer, treeId, parents, message);
    inserter.flush();
    marks.put(mark, commitId);
    fileMaps.put(mark, files);
    LOGGER.debug("Inserted commit mark :{} → {}.", mark, commitId);

    setRef(repository, ref, commitId);
  }

  private static void readTag(InputStream stream, ObjectInserter inserter,
      Map<Integer, ObjectId> marks, InMemoryRepository repository, String tagName)
      throws IOException {
    String line = readLine(stream);
    Integer markNum = null;
    if (line != null && line.startsWith("mark :")) {
      markNum = Integer.parseInt(line.substring("mark :".length()));
      line = readLine(stream);
    }
    checkState(line != null && line.startsWith("from "), "Expected from in tag, got: %s", line);
    final String fromStr = line.substring("from ".length());
    final ObjectId taggedId = fromStr.startsWith(":")
        ? marks.get(Integer.parseInt(fromStr.substring(1)))
        : ObjectId.fromString(fromStr);

    line = readLine(stream);
    PersonIdent tagger = null;
    if (line != null && line.startsWith("tagger ")) {
      tagger = parseIdent(line.substring("tagger ".length()));
      line = readLine(stream);
    }

    checkState(line != null && line.startsWith("data "), "Expected data in tag, got: %s", line);
    final int msgLength = Integer.parseInt(line.substring("data ".length()));
    final String message = new String(readExactlyBytes(stream, msgLength), StandardCharsets.UTF_8);

    final TagBuilder tagBuilder = new TagBuilder();
    tagBuilder.setTag(tagName);
    tagBuilder.setObjectId(taggedId, Constants.OBJ_COMMIT);
    if (tagger != null) {
      tagBuilder.setTagger(tagger);
    }
    tagBuilder.setMessage(message);
    final ObjectId tagId = inserter.insert(tagBuilder);
    inserter.flush();
    if (markNum != null) {
      marks.put(markNum, tagId);
    }

    final RefUpdate updateRef = repository.updateRef("refs/tags/" + tagName);
    updateRef.setNewObjectId(tagId);
    final Result result = updateRef.forceUpdate();
    verify(result == Result.NEW || result == Result.FORCED, result.toString());
  }

  private static String readLine(InputStream stream) throws IOException {
    final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    int b;
    while ((b = stream.read()) != -1) {
      if (b == '\n') {
        break;
      }
      buf.write(b);
    }
    if (b == -1 && buf.size() == 0) {
      return null;
    }
    return buf.toString(StandardCharsets.US_ASCII);
  }

  private static int readMark(InputStream stream) throws IOException {
    final String line = readLine(stream);
    checkState(line != null && line.startsWith("mark :"), "Expected mark line, got: %s", line);
    return Integer.parseInt(line.substring("mark :".length()));
  }

  private static int readDataLength(InputStream stream) throws IOException {
    final String line = readLine(stream);
    checkState(line != null && line.startsWith("data "), "Expected data line, got: %s", line);
    return Integer.parseInt(line.substring("data ".length()));
  }

  private static byte[] readExactlyBytes(InputStream stream, int length) throws IOException {
    final byte[] buf = stream.readNBytes(length);
    checkState(buf.length == length, "Expected %s bytes, got %s", length, buf.length);
    return buf;
  }

  private static PersonIdent readIdent(InputStream stream, String prefix) throws IOException {
    final String line = readLine(stream);
    checkState(line != null && line.startsWith(prefix + " "), "Expected %s line, got: %s", prefix,
        line);
    return parseIdent(line.substring(prefix.length() + 1));
  }

  private static PersonIdent parseIdent(String rest) {
    final int lt = rest.indexOf('<');
    final int gt = rest.indexOf('>');
    final String name = rest.substring(0, lt).trim();
    final String email = rest.substring(lt + 1, gt);
    final String[] timeParts = rest.substring(gt + 2).split(" ");
    return new PersonIdent(name, email, Instant.ofEpochSecond(Long.parseLong(timeParts[0])),
        ZoneOffset.of(timeParts[1]));
  }

  private sealed interface TreeNode permits FileNode, DirNode {}

  private record FileNode(MEntry entry) implements TreeNode {}

  private record DirNode(LinkedHashMap<String, TreeNode> children) implements TreeNode {}

  private static ObjectId insertTree(ObjectInserter inserter, Map<String, MEntry> files)
      throws IOException {
    final DirNode root = new DirNode(new LinkedHashMap<>());
    for (Map.Entry<String, MEntry> entry : files.entrySet()) {
      putEntry(root, entry.getKey(), entry.getValue());
    }
    return insertDirNode(inserter, root);
  }

  private static void putEntry(DirNode dir, String path, MEntry entry) {
    final int slash = path.indexOf('/');
    if (slash < 0) {
      dir.children().put(path, new FileNode(entry));
    } else {
      final DirNode subtree = (DirNode) dir.children().computeIfAbsent(
          path.substring(0, slash), k -> new DirNode(new LinkedHashMap<>()));
      putEntry(subtree, path.substring(slash + 1), entry);
    }
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

  private static void setRef(Repository repository, String ref, ObjectId newId)
      throws IOException {
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
