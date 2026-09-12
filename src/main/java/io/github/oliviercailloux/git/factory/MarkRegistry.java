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
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class MarkRegistry {
  @SuppressWarnings("unused")
  private static final Logger LOGGER = LoggerFactory.getLogger(MarkRegistry.class);

  static MarkRegistry create() {
    return new MarkRegistry();
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

  private static String readLineRequired(InputStream stream) throws IOException {
    final String line = readLine(stream);
    checkState(line != null, "Unexpected end of stream");
    return line;
  }

  private static byte[] readExactlyBytes(InputStream stream, int length) throws IOException {
    final byte[] buf = stream.readNBytes(length);
    checkState(buf.length == length, "Expected %s bytes, got %s", length, buf.length);
    return buf;
  }

  private static int parseDataLength(String line) {
    checkState(line != null && line.startsWith("data "), "Expected data line, got: %s", line);
    final String lengthOrDelimiter = line.substring("data ".length());
    checkState(!lengthOrDelimiter.startsWith("<<"),
        "Delimited data format is not supported, only the exact-byte-count form: %s", line);
    return Integer.parseInt(lengthOrDelimiter);
  }

  private static int readDataLength(InputStream stream) throws IOException {
    return parseDataLength(readLineRequired(stream));
  }

  private static int readMark(InputStream stream) throws IOException {
    final String line = readLineRequired(stream);
    checkState(line.startsWith("mark :"), "Expected mark line, got: %s", line);
    return Integer.parseInt(line.substring("mark :".length()));
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

  private static PersonIdent readIdent(InputStream stream, String prefix) throws IOException {
    final String line = readLineRequired(stream);
    checkState(line.startsWith(prefix + " "), "Expected %s line, got: %s", prefix, line);
    return parseIdent(line.substring(prefix.length() + 1));
  }

  /**
   * Parses a fast-import file mode: only the full octal forms git actually recognizes for a tree
   * entry ({@code 100644}, {@code 100755}, {@code 120000}, {@code 160000}, {@code 040000}) are
   * accepted; shorthand forms (e.g. {@code 644}) are rejected, rather than silently producing a
   * bogus mode.
   */
  private static FileMode parseMode(String bitsToken) {
    final FileMode mode = FileMode.fromBits(Integer.parseInt(bitsToken, 8));
    checkState(
        mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE
            || mode == FileMode.SYMLINK || mode == FileMode.GITLINK || mode == FileMode.TREE,
        "Unsupported or invalid file mode (expected one of 100644, 100755, 120000, 160000, 040000): %s",
        bitsToken);
    return mode;
  }

  /**
   * Returns the index right after the path token starting at {@code start}: past the closing quote
   * if the token is quoted, otherwise the index of the next space (or end of string). Unquoted
   * tokens cannot contain a space, per the fast-import format.
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

  private static String parsePath(String token) {
    return token.startsWith("\"") ? unquotePath(token) : token;
  }

  /** Reads the flat path → entry map of an already-inserted commit's tree. */
  private static Map<String, MEntry> filesOf(Repository repository, ObjectId commitId)
      throws IOException {
    final Map<String, MEntry> files = new LinkedHashMap<>();
    try (RevWalk revWalk = new RevWalk(repository); TreeWalk treeWalk = new TreeWalk(repository)) {
      final RevCommit commit = revWalk.parseCommit(commitId);
      treeWalk.addTree(commit.getTree());
      treeWalk.setRecursive(true);
      while (treeWalk.next()) {
        files.put(treeWalk.getPathString(),
            new MEntry(treeWalk.getFileMode(0), treeWalk.getObjectId(0)));
      }
    }
    return files;
  }

  private final Map<Integer, ObjectId> marks = new HashMap<>();

  private MarkRegistry() {}

  /**
   * Resolves a fast-import {@code <commit-ish>}: a mark reference ({@code :N}), or else anything
   * JGit's own revision resolution understands — a branch/tag name, a full or abbreviated SHA-1, a
   * {@code ^0}-style suffix, and so on.
   */
  private ObjectId resolveCommitish(Repository repository, String commitish) throws IOException {
    if (commitish.startsWith(":")) {
      final ObjectId oid = marks.get(Integer.parseInt(commitish.substring(1)));
      checkState(oid != null, "Unknown mark: %s", commitish);
      return oid;
    }
    final ObjectId resolved = repository.resolve(commitish);
    checkState(resolved != null, "Could not resolve commit-ish: %s", commitish);
    return resolved;
  }

  void readBlob(InputStream stream, ObjectInserter inserter) throws IOException {
    final int mark = readMark(stream);
    final int length = readDataLength(stream);
    final byte[] content = readExactlyBytes(stream, length);
    final ObjectId oid = inserter.insert(Constants.OBJ_BLOB, content);
    marks.put(mark, oid);
    LOGGER.debug("Inserted blob mark :{} → {}.", mark, oid);
  }

  /**
   * Reads the optional {@code from} line following a {@code reset <ref>} line and, if present,
   * updates (or deletes, for the null SHA-1) the given ref accordingly. Returns the next unconsumed
   * line, since the {@code from} line is optional and whatever follows it (or the {@code reset}
   * line itself) belongs to the next top-level command.
   */
  String readReset(InputStream stream, Repository repository, String ref) throws IOException {
    String line = readLine(stream);
    if (line != null && line.startsWith("from ")) {
      final ObjectId target = resolveCommitish(repository, line.substring("from ".length()));
      if (ObjectId.zeroId().equals(target)) {
        final RefUpdate updateRef = repository.updateRef(ref);
        updateRef.setForceUpdate(true);
        final Result result = updateRef.delete();
        verify(result == Result.FORCED || result == Result.NO_CHANGE, result.toString());
      } else {
        FastImporter.setRef(repository, ref, target);
      }
      line = readLine(stream);
    }
    return line;
  }

  void readCommit(InputStream stream, ObjectInserter inserter, Repository repository, String ref)
      throws IOException {
    final int mark = readMark(stream);
    final PersonIdent author = readIdent(stream, "author");
    final PersonIdent committer = readIdent(stream, "committer");
    String line = readLineRequired(stream);
    if (line.startsWith("encoding ")) {
      line = readLineRequired(stream);
    }
    final int msgLength = parseDataLength(line);
    final String message = new String(readExactlyBytes(stream, msgLength), StandardCharsets.UTF_8);

    line = readLineRequired(stream);
    /*
     * Official format doc (https://git-scm.com/docs/git-fast-import#_data): trailing LF after <raw>
     * is optional and not counted in the byte count; skip any such blank lines.
     */
    while ("".equals(line)) {
      line = readLineRequired(stream);
    }
    final List<ObjectId> parents = new ArrayList<>();
    ObjectId firstParent = null;
    if (line.startsWith("from ")) {
      firstParent = resolveCommitish(repository, line.substring("from ".length()));
      parents.add(firstParent);
      line = readLineRequired(stream);
    }
    while (line.startsWith("merge ")) {
      parents.add(resolveCommitish(repository, line.substring("merge ".length())));
      line = readLineRequired(stream);
    }

    final Map<String, MEntry> files = new LinkedHashMap<>();
    if ("deleteall".equals(line)) {
      line = readLineRequired(stream);
    } else if (firstParent != null) {
      files.putAll(filesOf(repository, firstParent));
    }

    while (line != null && !line.isEmpty()) {
      if (line.startsWith("M ")) {
        final String[] parts = line.split(" ", 4);
        final FileMode mode = parseMode(parts[1]);
        checkState(!"inline".equals(parts[2]), "Inline filemodify data is not supported: %s", line);
        final ObjectId oid = parts[2].startsWith(":")
            ? marks.get(Integer.parseInt(parts[2].substring(1))) : ObjectId.fromString(parts[2]);
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
      } else if (!line.startsWith("#")) {
        checkState(false, "Unsupported file-change command: %s", line);
      }
      line = readLine(stream);
    }

    final ObjectId treeId = FastImporter.insertTree(inserter, files);
    final ObjectId commitId =
        FactoGitNew.insertCommit(inserter, author, committer, treeId, parents, message);
    inserter.flush();
    marks.put(mark, commitId);
    LOGGER.debug("Inserted commit mark :{} → {}.", mark, commitId);

    FastImporter.setRef(repository, ref, commitId);
  }

  void readTag(InputStream stream, ObjectInserter inserter, Repository repository, String tagName)
      throws IOException {
    String line = readLineRequired(stream);
    Integer markNum = null;
    if (line.startsWith("mark :")) {
      markNum = Integer.parseInt(line.substring("mark :".length()));
      line = readLineRequired(stream);
    }
    checkState(line.startsWith("from "), "Expected from in tag, got: %s", line);
    final ObjectId taggedId = resolveCommitish(repository, line.substring("from ".length()));

    line = readLineRequired(stream);
    PersonIdent tagger = null;
    if (line.startsWith("tagger ")) {
      tagger = parseIdent(line.substring("tagger ".length()));
      line = readLineRequired(stream);
    }

    final int msgLength = parseDataLength(line);
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
}
