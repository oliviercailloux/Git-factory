package io.github.oliviercailloux.git.factory;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;

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
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.TagBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MarkRegistry {
  private static sealed interface Mark permits OidMark, CommitMark {
    ObjectId oid();
  }

  private static record OidMark(ObjectId oid) implements Mark {}

  private static record CommitMark(ObjectId oid, Map<String, MEntry> files) implements Mark {}

  @SuppressWarnings("unused")
  private static final Logger LOGGER = LoggerFactory.getLogger(MarkRegistry.class);

  private final Map<Integer, Mark> marks = new HashMap<>();

  void readBlob(InputStream stream, ObjectInserter inserter) throws IOException {
    final int mark = readMark(stream);
    final int length = readDataLength(stream);
    final byte[] content = readExactlyBytes(stream, length);
    final ObjectId oid = inserter.insert(Constants.OBJ_BLOB, content);
    marks.put(mark, new OidMark(oid));
    LOGGER.debug("Inserted blob mark :{} → {}.", mark, oid);
  }

  void readCommit(InputStream stream, ObjectInserter inserter,
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
    final String message =
        new String(readExactlyBytes(stream, msgLength), StandardCharsets.UTF_8);

    line = readLine(stream);
    final List<ObjectId> parents = new ArrayList<>();
    Integer firstParentMark = null;
    if (line != null && line.startsWith("from :")) {
      firstParentMark = Integer.parseInt(line.substring("from :".length()));
      parents.add(marks.get(firstParentMark).oid());
      line = readLine(stream);
    }
    while (line != null && line.startsWith("merge :")) {
      parents.add(marks.get(Integer.parseInt(line.substring("merge :".length()))).oid());
      line = readLine(stream);
    }

    final Map<String, MEntry> files = new LinkedHashMap<>();
    if ("deleteall".equals(line)) {
      line = readLine(stream);
    } else {
      if (marks.get(firstParentMark) instanceof CommitMark cm) {
        files.putAll(cm.files());
      }
    }

    while (line != null && !line.isEmpty()) {
      if (line.startsWith("M ")) {
        final String[] parts = line.split(" ", 4);
        final FileMode mode = FileMode.fromBits(Integer.parseInt(parts[1], 8));
        final ObjectId oid = parts[2].startsWith(":")
            ? marks.get(Integer.parseInt(parts[2].substring(1))).oid()
            : ObjectId.fromString(parts[2]);
        files.put(parsePath(parts[3]), new MEntry(mode, oid));
      } else if (line.startsWith("D ")) {
        files.remove(parsePath(line.substring("D ".length())));
      } else if (line.startsWith("R ") || line.startsWith("C ")) {
        final int sourceEnd = pathTokenEnd(line, 2);
        final String source = parsePath(line.substring(2, sourceEnd));
        checkState(sourceEnd < line.length() && line.charAt(sourceEnd) == ' ',
            "Expected source path followed by a space, got: %s", line);
        final String dest = parsePath(line.substring(sourceEnd + 1));
        if (line.startsWith("R ")) {
          files.put(dest, files.remove(source));
        } else {
          files.put(dest, files.get(source));
        }
      }
      line = readLine(stream);
    }

    final ObjectId treeId = FastImporter.insertTree(inserter, files);
    final ObjectId commitId =
        FactoGitNew.insertCommit(inserter, author, committer, treeId, parents, message);
    inserter.flush();
    marks.put(mark, new CommitMark(commitId, files));
    LOGGER.debug("Inserted commit mark :{} → {}.", mark, commitId);

    FastImporter.setRef(repository, ref, commitId);
  }

  void readTag(InputStream stream, ObjectInserter inserter,
      InMemoryRepository repository, String tagName) throws IOException {
    String line = readLine(stream);
    Integer markNum = null;
    if (line != null && line.startsWith("mark :")) {
      markNum = Integer.parseInt(line.substring("mark :".length()));
      line = readLine(stream);
    }
    checkState(line != null && line.startsWith("from "), "Expected from in tag, got: %s", line);
    final String fromStr = line.substring("from ".length());
    final ObjectId taggedId = fromStr.startsWith(":")
        ? marks.get(Integer.parseInt(fromStr.substring(1))).oid()
        : ObjectId.fromString(fromStr);

    line = readLine(stream);
    PersonIdent tagger = null;
    if (line != null && line.startsWith("tagger ")) {
      tagger = parseIdent(line.substring("tagger ".length()));
      line = readLine(stream);
    }

    checkState(line != null && line.startsWith("data "), "Expected data in tag, got: %s", line);
    final int msgLength = Integer.parseInt(line.substring("data ".length()));
    final String message =
        new String(readExactlyBytes(stream, msgLength), StandardCharsets.UTF_8);

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
      marks.put(markNum, new OidMark(tagId));
    }

    final RefUpdate updateRef = repository.updateRef("refs/tags/" + tagName);
    updateRef.setNewObjectId(tagId);
    final Result result = updateRef.forceUpdate();
    verify(result == Result.NEW || result == Result.FORCED, result.toString());
  }

  static String readLine(InputStream stream) throws IOException {
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

  /**
   * Returns the index right after the path token starting at {@code start}: past the closing
   * quote if the token is quoted, otherwise the index of the next space (or end of string).
   * Unquoted tokens cannot contain a space, per the fast-import format.
   */
  private static int pathTokenEnd(String s, int start) {
    if (s.charAt(start) == '"') {
      int i = start + 1;
      while (i < s.length() && s.charAt(i) != '"') {
        if (s.charAt(i) == '\\') {
          i++;
        }
        i++;
      }
      checkState(i < s.length(), "Unterminated quoted path: %s", s);
      return i + 1;
    }
    final int sp = s.indexOf(' ', start);
    return sp < 0 ? s.length() : sp;
  }

  private static String parsePath(String token) {
    return token.startsWith("\"") ? unquotePath(token) : token;
  }

  /** Unquotes a C-style quoted fast-import path, including its surrounding double quotes. */
  private static String unquotePath(String quoted) {
    checkState(quoted.length() >= 2 && quoted.charAt(quoted.length() - 1) == '"',
        "Malformed quoted path: %s", quoted);
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    final String inner = quoted.substring(1, quoted.length() - 1);
    int i = 0;
    while (i < inner.length()) {
      final char c = inner.charAt(i);
      if (c != '\\') {
        bytes.write(c);
        i++;
        continue;
      }
      i++;
      checkState(i < inner.length(), "Truncated escape sequence in path: %s", quoted);
      final char e = inner.charAt(i);
      switch (e) {
        case '\\' -> bytes.write('\\');
        case '"' -> bytes.write('"');
        case 'n' -> bytes.write('\n');
        case 'a' -> bytes.write(7);
        case 'b' -> bytes.write('\b');
        case 'f' -> bytes.write('\f');
        case 'r' -> bytes.write('\r');
        case 't' -> bytes.write('\t');
        case 'v' -> bytes.write(11);
        default -> {
          checkState(Character.isDigit(e) && i + 2 < inner.length(),
              "Unknown escape sequence '\\%s' in path: %s", e, quoted);
          bytes.write(Integer.parseInt(inner.substring(i, i + 3), 8));
          i += 2;
        }
      }
      i++;
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }
}
