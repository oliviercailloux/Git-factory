package io.github.oliviercailloux.git.factory;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

import com.google.common.io.CharSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
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
import org.eclipse.jgit.lib.TreeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FastImporter {
  @SuppressWarnings("unused")
  private static final Logger LOGGER = LoggerFactory.getLogger(FastImporter.class);

  public static FastImporter create() {
    return new FastImporter();
  }

  private FastImporter() {}

  public DfsRepository importRepository(CharSource source) throws IOException {
    final InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription(""));
    repository.create(true);

    final Map<Integer, ObjectId> marks = new HashMap<>();

    try (BufferedReader reader = source.openBufferedStream();
        ObjectInserter inserter = repository.getObjectDatabase().newInserter()) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }
        if (line.equals("blob")) {
          readBlob(reader, inserter, marks);
        } else if (line.startsWith("reset ")) {
          // skip
        } else if (line.startsWith("commit ")) {
          final String ref = line.substring("commit ".length());
          readCommit(reader, inserter, marks, repository, ref);
        }
      }
    }

    return repository;
  }

  private static void readBlob(BufferedReader reader, ObjectInserter inserter,
      Map<Integer, ObjectId> marks) throws IOException {
    final int mark = readMark(reader);
    final int length = readDataLength(reader);
    final String content = readExactly(reader, length);
    final ObjectId oid =
        inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
    marks.put(mark, oid);
    LOGGER.debug("Inserted blob mark :{} → {}.", mark, oid);
  }

  private static void readCommit(BufferedReader reader, ObjectInserter inserter,
      Map<Integer, ObjectId> marks, InMemoryRepository repository, String ref) throws IOException {
    final int mark = readMark(reader);
    final PersonIdent author = readIdent(reader, "author");
    skipPrefix(reader, "committer ");
    final int msgLength = readDataLength(reader);
    final String message = readExactly(reader, msgLength);

    final String deleteAllLine = reader.readLine();
    verify("deleteall".equals(deleteAllLine), "Expected deleteall, got: %s", deleteAllLine);

    final Map<String, ObjectId> files = new LinkedHashMap<>();
    String line;
    while ((line = reader.readLine()) != null && !line.isEmpty()) {
      if (line.startsWith("M ")) {
        final String[] parts = line.split(" ", 4);
        final int blobMark = Integer.parseInt(parts[2].substring(1));
        files.put(parts[3], marks.get(blobMark));
      }
    }

    final ObjectId treeId = insertTree(inserter, files);
    final ObjectId commitId = insertCommit(inserter, author, treeId, List.of(), message);
    marks.put(mark, commitId);
    LOGGER.debug("Inserted commit mark :{} → {}.", mark, commitId);

    if (ref.equals("refs/heads/main")) {
      setMainAndHead(repository, commitId);
    }
  }

  private static int readMark(BufferedReader reader) throws IOException {
    final String line = reader.readLine();
    checkState(line != null && line.startsWith("mark :"), "Expected mark line, got: %s", line);
    return Integer.parseInt(line.substring("mark :".length()));
  }

  private static int readDataLength(BufferedReader reader) throws IOException {
    final String line = reader.readLine();
    checkState(line != null && line.startsWith("data "), "Expected data line, got: %s", line);
    return Integer.parseInt(line.substring("data ".length()));
  }

  private static String readExactly(BufferedReader reader, int length) throws IOException {
    final char[] buf = new char[length];
    int read = 0;
    while (read < length) {
      final int n = reader.read(buf, read, length - read);
      checkState(n != -1, "Unexpected end of stream reading data block of length %s", length);
      read += n;
    }
    return new String(buf);
  }

  private static PersonIdent readIdent(BufferedReader reader, String prefix) throws IOException {
    final String line = reader.readLine();
    checkState(line != null && line.startsWith(prefix + " "), "Expected %s line, got: %s", prefix,
        line);
    final String rest = line.substring(prefix.length() + 1);
    final int lt = rest.indexOf('<');
    final int gt = rest.indexOf('>');
    final String name = rest.substring(0, lt).trim();
    final String email = rest.substring(lt + 1, gt);
    final String[] timeParts = rest.substring(gt + 2).split(" ");
    return new PersonIdent(name, email, Instant.ofEpochSecond(Long.parseLong(timeParts[0])),
        ZoneOffset.of(timeParts[1]));
  }

  private static void skipPrefix(BufferedReader reader, String prefix) throws IOException {
    final String line = reader.readLine();
    checkState(line != null && line.startsWith(prefix), "Expected line starting with %s, got: %s",
        prefix, line);
  }

  private static ObjectId insertTree(ObjectInserter inserter, Map<String, ObjectId> files)
      throws IOException {
    final Map<String, Object> tree = new LinkedHashMap<>();
    for (Map.Entry<String, ObjectId> entry : files.entrySet()) {
      final String path = entry.getKey();
      final int slash = path.indexOf('/');
      if (slash < 0) {
        tree.put(path, entry.getValue());
      } else {
        @SuppressWarnings("unchecked")
        final Map<String, Object> subtree = (Map<String, Object>) tree.computeIfAbsent(
            path.substring(0, slash), k -> new LinkedHashMap<>());
        subtree.put(path.substring(slash + 1), entry.getValue());
      }
    }
    return insertTreeNode(inserter, tree);
  }

  @SuppressWarnings("unchecked")
  private static ObjectId insertTreeNode(ObjectInserter inserter, Map<String, Object> tree)
      throws IOException {
    final TreeFormatter formatter = new TreeFormatter();
    for (Map.Entry<String, Object> entry : tree.entrySet()) {
      if (entry.getValue() instanceof ObjectId oid) {
        formatter.append(entry.getKey(), FileMode.REGULAR_FILE, oid);
      } else {
        formatter.append(entry.getKey(), FileMode.TREE,
            insertTreeNode(inserter, (Map<String, Object>) entry.getValue()));
      }
    }
    return inserter.insert(formatter);
  }

  private static ObjectId insertCommit(ObjectInserter inserter, PersonIdent author,
      ObjectId treeId, List<ObjectId> parents, String message) throws IOException {
    final CommitBuilder commitBuilder = new CommitBuilder();
    commitBuilder.setMessage(message);
    commitBuilder.setAuthor(author);
    commitBuilder.setCommitter(author);
    commitBuilder.setTreeId(treeId);
    for (ObjectId parent : parents) {
      commitBuilder.addParentId(parent);
    }
    final ObjectId commitId = inserter.insert(commitBuilder);
    inserter.flush();
    return commitId;
  }

  private static void setMainAndHead(Repository repository, ObjectId newId) throws IOException {
    {
      final RefUpdate updateRef = repository.updateRef("refs/heads/main");
      updateRef.setNewObjectId(newId);
      final Result result = updateRef.update();
      verify(result == Result.NEW, result.toString());
    }
    {
      final RefUpdate updateRef = repository.updateRef(Constants.HEAD);
      final Result result = updateRef.link("refs/heads/main");
      verify(result == Result.FORCED, result.toString());
    }
  }
}
