import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class Main {
  static class TreeEntry {
    String mode;
    String name;
    byte[] shaBytes;

    TreeEntry(String mode, String name, byte[] shaBytes) {
      this.mode = mode;
      this.name = name;
      this.shaBytes = shaBytes;
    }

    boolean isDir() {
      return "40000".equals(mode);
    }
  }

  static class GitObject {
    String type;
    byte[] data;
    String sha;

    GitObject(String type, byte[] data, String sha) {
      this.type = type;
      this.data = data;
      this.sha = sha;
    }
  }

  static class RawDelta {
    int type; // 6 (OFS) or 7 (REF)
    int startOffset;
    String baseSha;
    int baseOffset;
    byte[] deltaData;

    RawDelta(int type, int startOffset, String baseSha, int baseOffset, byte[] deltaData) {
      this.type = type;
      this.startOffset = startOffset;
      this.baseSha = baseSha;
      this.baseOffset = baseOffset;
      this.deltaData = deltaData;
    }
  }

  public static void main(String[] args) {
    if (args.length == 0) {
      System.err.println("Usage: ./your_program.sh <command> [<args>]");
      System.exit(1);
    }
    final String command = args[0];

    switch (command) {
      case "init" -> {
        final File root = new File(".git");
        new File(root, "objects").mkdirs();
        new File(root, "refs").mkdirs();
        final File head = new File(root, "HEAD");

        try {
          head.createNewFile();
          Files.write(head.toPath(), "ref: refs/heads/main\n".getBytes());
          System.out.println("Initialized git directory");
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
      case "cat-file" -> {
        if (args.length >= 3 && args[1].equals("-p")) {
          String sha = args[2];
          byte[] decompressed = readObject(sha);
          int nullIndex = -1;
          for (int i = 0; i < decompressed.length; i++) {
            if (decompressed[i] == 0) {
              nullIndex = i;
              break;
            }
          }
          if (nullIndex != -1) {
            System.out.write(decompressed, nullIndex + 1, decompressed.length - (nullIndex + 1));
            System.out.flush();
          }
        }
      }
      case "hash-object" -> {
        boolean write = false;
        String filePath = null;
        for (int i = 1; i < args.length; i++) {
          if (args[i].equals("-w")) {
            write = true;
          } else {
            filePath = args[i];
          }
        }
        if (filePath != null) {
          try {
            byte[] fileBytes = Files.readAllBytes(new File(filePath).toPath());
            byte[] header = ("blob " + fileBytes.length + "\0").getBytes(StandardCharsets.UTF_8);
            byte[] fullData = new byte[header.length + fileBytes.length];
            System.arraycopy(header, 0, fullData, 0, header.length);
            System.arraycopy(fileBytes, 0, fullData, header.length, fileBytes.length);

            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(fullData);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
              sb.append(String.format("%02x", b));
            }
            String sha = sb.toString();

            if (write) {
              writeObject(sha, fullData);
            }
            System.out.println(sha);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
      case "ls-tree" -> {
        boolean nameOnly = false;
        String treeSha = null;
        for (int i = 1; i < args.length; i++) {
          if (args[i].equals("--name-only")) {
            nameOnly = true;
          } else {
            treeSha = args[i];
          }
        }
        if (treeSha != null) {
          byte[] decompressed = readObject(treeSha);
          int nullIndex = -1;
          for (int i = 0; i < decompressed.length; i++) {
            if (decompressed[i] == 0) {
              nullIndex = i;
              break;
            }
          }
          if (nullIndex != -1) {
            int ptr = nullIndex + 1;
            while (ptr < decompressed.length) {
              int spaceIdx = -1;
              for (int i = ptr; i < decompressed.length; i++) {
                if (decompressed[i] == ' ') {
                  spaceIdx = i;
                  break;
                }
              }
              if (spaceIdx == -1) break;
              String mode = new String(decompressed, ptr, spaceIdx - ptr, StandardCharsets.UTF_8);

              int entryNullIdx = -1;
              for (int i = spaceIdx + 1; i < decompressed.length; i++) {
                if (decompressed[i] == 0) {
                  entryNullIdx = i;
                  break;
                }
              }
              if (entryNullIdx == -1) break;
              String name = new String(decompressed, spaceIdx + 1, entryNullIdx - (spaceIdx + 1), StandardCharsets.UTF_8);

              byte[] shaBytes = new byte[20];
              System.arraycopy(decompressed, entryNullIdx + 1, shaBytes, 0, 20);
              StringBuilder sb = new StringBuilder();
              for (byte b : shaBytes) {
                sb.append(String.format("%02x", b));
              }
              String shaHex = sb.toString();

              ptr = entryNullIdx + 1 + 20;

              if (nameOnly) {
                System.out.println(name);
              } else {
                String formattedMode = mode.length() == 5 ? "0" + mode : mode;
                String type = mode.startsWith("4") || mode.startsWith("04") ? "tree" : "blob";
                System.out.println(formattedMode + " " + type + " " + shaHex + "\t" + name);
              }
            }
          }
        }
      }
      case "write-tree" -> {
        try {
          File workingDir = new File(".");
          byte[] rootTreeSha = writeTree(workingDir);
          StringBuilder sb = new StringBuilder();
          for (byte b : rootTreeSha) {
            sb.append(String.format("%02x", b));
          }
          System.out.println(sb.toString());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      case "commit-tree" -> {
        try {
          String treeSha = args[1];
          String parentSha = null;
          String message = null;
          for (int i = 2; i < args.length; i++) {
            if (args[i].equals("-p") && i + 1 < args.length) {
              parentSha = args[++i];
            } else if (args[i].equals("-m") && i + 1 < args.length) {
              message = args[++i];
            }
          }

          StringBuilder content = new StringBuilder();
          content.append("tree ").append(treeSha).append("\n");
          if (parentSha != null) {
            content.append("parent ").append(parentSha).append("\n");
          }
          long timestamp = System.currentTimeMillis() / 1000;
          String authorLine = "author John Doe <john@example.com> " + timestamp + " +0000\n";
          String committerLine = "committer John Doe <john@example.com> " + timestamp + " +0000\n";
          content.append(authorLine);
          content.append(committerLine);
          content.append("\n");
          content.append(message != null ? message : "").append("\n");

          byte[] contentBytes = content.toString().getBytes(StandardCharsets.UTF_8);
          byte[] header = ("commit " + contentBytes.length + "\0").getBytes(StandardCharsets.UTF_8);
          byte[] fullData = new byte[header.length + contentBytes.length];
          System.arraycopy(header, 0, fullData, 0, header.length);
          System.arraycopy(contentBytes, 0, fullData, header.length, contentBytes.length);

          MessageDigest md = MessageDigest.getInstance("SHA-1");
          byte[] digest = md.digest(fullData);
          StringBuilder sb = new StringBuilder();
          for (byte b : digest) {
            sb.append(String.format("%02x", b));
          }
          String commitSha = sb.toString();

          writeObject(commitSha, fullData);
          System.out.println(commitSha);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
      case "clone" -> {
        if (args.length >= 3) {
          String repoUrl = args[1];
          String targetDir = args[2];
          try {
            cloneRepository(repoUrl, targetDir);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
      default -> System.out.println("Unknown command: " + command);
    }
  }

  private static void cloneRepository(String repoUrl, String targetDirPath) throws Exception {
    File targetDir = new File(targetDirPath);
    targetDir.mkdirs();
    File gitDir = new File(targetDir, ".git");
    File objectsDir = new File(gitDir, "objects");
    File refsHeadsDir = new File(gitDir, "refs/heads");
    objectsDir.mkdirs();
    refsHeadsDir.mkdirs();

    HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build();

    // 1. Discover references via info/refs
    String infoRefsUrl = repoUrl.endsWith("/") ? repoUrl + "info/refs?service=git-upload-pack" : repoUrl + "/info/refs?service=git-upload-pack";
    HttpRequest getReq = HttpRequest.newBuilder()
        .uri(URI.create(infoRefsUrl))
        .header("User-Agent", "git/2.34.1")
        .GET()
        .build();
    HttpResponse<byte[]> getResp = client.send(getReq, HttpResponse.BodyHandlers.ofByteArray());
    byte[] refData = getResp.body();

    // Parse pkt-lines
    List<byte[]> pktLines = parsePktLines(refData);
    String headSha = null;
    String headRef = null;
    Map<String, String> refMap = new HashMap<>();

    for (byte[] lineBytes : pktLines) {
      if (lineBytes == null || lineBytes.length < 40) continue;
      String line = new String(lineBytes, StandardCharsets.UTF_8);
      int nullIdx = line.indexOf('\0');
      String mainPart = nullIdx != -1 ? line.substring(0, nullIdx) : line;
      mainPart = mainPart.trim();

      String[] parts = mainPart.split(" ");
      if (parts.length == 2 && parts[0].length() == 40) {
        String sha = parts[0];
        String ref = parts[1];
        refMap.put(ref, sha);
        if (ref.equals("HEAD")) {
          headSha = sha;
        }
      }
      if (nullIdx != -1) {
        String caps = line.substring(nullIdx + 1);
        for (String token : caps.split(" ")) {
          if (token.startsWith("symref=HEAD:")) {
            headRef = token.substring("symref=HEAD:".length()).trim();
          }
        }
      }
    }

    if (headSha == null && refMap.containsKey("HEAD")) {
      headSha = refMap.get("HEAD");
    }

    if (headRef == null) {
      for (Map.Entry<String, String> entry : refMap.entrySet()) {
        if (!entry.getKey().equals("HEAD") && entry.getValue().equals(headSha)) {
          headRef = entry.getKey();
          break;
        }
      }
      if (headRef == null) {
        headRef = "refs/heads/master";
      }
    }

    // Write HEAD and branch ref
    Files.write(new File(gitDir, "HEAD").toPath(), ("ref: " + headRef + "\n").getBytes(StandardCharsets.UTF_8));
    File branchRefFile = new File(gitDir, headRef);
    branchRefFile.getParentFile().mkdirs();
    Files.write(branchRefFile.toPath(), (headSha + "\n").getBytes(StandardCharsets.UTF_8));

    // 2. Fetch packfile via git-upload-pack
    String uploadPackUrl = repoUrl.endsWith("/") ? repoUrl + "git-upload-pack" : repoUrl + "/git-upload-pack";
    String postBody = "0032want " + headSha + "\n00000009done\n";
    HttpRequest postReq = HttpRequest.newBuilder()
        .uri(URI.create(uploadPackUrl))
        .header("User-Agent", "git/2.34.1")
        .header("Content-Type", "application/x-git-upload-pack-request")
        .header("Accept", "application/x-git-upload-pack-result")
        .POST(HttpRequest.BodyPublishers.ofByteArray(postBody.getBytes(StandardCharsets.US_ASCII)))
        .build();
    HttpResponse<byte[]> postResp = client.send(postReq, HttpResponse.BodyHandlers.ofByteArray());
    byte[] packResp = postResp.body();

    // Find PACK signature
    int packOffset = -1;
    for (int i = 0; i <= packResp.length - 4; i++) {
      if (packResp[i] == 'P' && packResp[i + 1] == 'A' && packResp[i + 2] == 'C' && packResp[i + 3] == 'K') {
        packOffset = i;
        break;
      }
    }
    if (packOffset == -1) {
      throw new RuntimeException("No PACK found in upload-pack response");
    }

    byte[] pack = Arrays.copyOfRange(packResp, packOffset, packResp.length);
    unpackPackfile(pack, gitDir);

    // 3. Checkout working tree from headSha
    byte[] commitObj = readObjectFrom(gitDir, headSha);
    int commitNull = -1;
    for (int i = 0; i < commitObj.length; i++) {
      if (commitObj[i] == 0) {
        commitNull = i;
        break;
      }
    }
    String commitContent = new String(commitObj, commitNull + 1, commitObj.length - (commitNull + 1), StandardCharsets.UTF_8);
    String treeSha = null;
    for (String line : commitContent.split("\n")) {
      if (line.startsWith("tree ")) {
        treeSha = line.substring(5).trim();
        break;
      }
    }
    if (treeSha != null) {
      checkoutTree(gitDir, targetDir, treeSha);
    }
  }

  private static List<byte[]> parsePktLines(byte[] data) {
    List<byte[]> lines = new ArrayList<>();
    int idx = 0;
    while (idx + 4 <= data.length) {
      String hex = new String(data, idx, 4, StandardCharsets.US_ASCII);
      int len;
      try {
        len = Integer.parseInt(hex, 16);
      } catch (NumberFormatException e) {
        break;
      }
      if (len == 0) {
        lines.add(null);
        idx += 4;
      } else {
        if (idx + len > data.length) break;
        lines.add(Arrays.copyOfRange(data, idx + 4, idx + len));
        idx += len;
      }
    }
    return lines;
  }

  private static void unpackPackfile(byte[] pack, File gitDir) throws Exception {
    int numObjects = ((pack[8] & 0xFF) << 24) | ((pack[9] & 0xFF) << 16) | ((pack[10] & 0xFF) << 8) | (pack[11] & 0xFF);
    int pos = 12;

    Map<String, GitObject> objectsBySha = new HashMap<>();
    Map<Integer, GitObject> objectsByOffset = new HashMap<>();
    List<RawDelta> deferredDeltas = new ArrayList<>();

    String[] typeNames = {"", "commit", "tree", "blob", "tag", "", "ofs-delta", "ref-delta"};

    for (int i = 0; i < numObjects; i++) {
      int objStartOffset = pos;
      int b = pack[pos++] & 0xFF;
      int objType = (b >> 4) & 7;
      long size = b & 0x0F;
      int shift = 4;
      while ((b & 0x80) != 0) {
        b = pack[pos++] & 0xFF;
        size |= (long) (b & 0x7F) << shift;
        shift += 7;
      }

      if (objType >= 1 && objType <= 4) {
        // Normal object
        Inflater inflater = new Inflater();
        inflater.setInput(pack, pos, pack.length - pos);
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) size);
        byte[] buf = new byte[4096];
        while (!inflater.finished()) {
          int count = inflater.inflate(buf);
          if (count > 0) {
            baos.write(buf, 0, count);
          } else {
            break;
          }
        }
        int consumed = (int) inflater.getBytesRead();
        inflater.end();
        pos += consumed;

        byte[] decompressed = baos.toByteArray();
        String typeName = typeNames[objType];
        byte[] header = (typeName + " " + decompressed.length + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] fullObj = new byte[header.length + decompressed.length];
        System.arraycopy(header, 0, fullObj, 0, header.length);
        System.arraycopy(decompressed, 0, fullObj, header.length, decompressed.length);

        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] digest = md.digest(fullObj);
        StringBuilder sb = new StringBuilder();
        for (byte d : digest) {
          sb.append(String.format("%02x", d));
        }
        String sha = sb.toString();

        writeObjectTo(gitDir, sha, fullObj);
        GitObject gitObj = new GitObject(typeName, decompressed, sha);
        objectsBySha.put(sha, gitObj);
        objectsByOffset.put(objStartOffset, gitObj);
      } else if (objType == 7) {
        // OBJ_REF_DELTA
        byte[] baseShaBytes = Arrays.copyOfRange(pack, pos, pos + 20);
        pos += 20;
        StringBuilder sb = new StringBuilder();
        for (byte d : baseShaBytes) {
          sb.append(String.format("%02x", d));
        }
        String baseSha = sb.toString();

        Inflater inflater = new Inflater();
        inflater.setInput(pack, pos, pack.length - pos);
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) size);
        byte[] buf = new byte[4096];
        while (!inflater.finished()) {
          int count = inflater.inflate(buf);
          if (count > 0) {
            baos.write(buf, 0, count);
          } else {
            break;
          }
        }
        int consumed = (int) inflater.getBytesRead();
        inflater.end();
        pos += consumed;

        deferredDeltas.add(new RawDelta(7, objStartOffset, baseSha, -1, baos.toByteArray()));
      } else if (objType == 6) {
        // OBJ_OFS_DELTA
        int c = pack[pos++] & 0xFF;
        long offset = c & 0x7F;
        while ((c & 0x80) != 0) {
          c = pack[pos++] & 0xFF;
          offset = ((offset + 1) << 7) | (c & 0x7F);
        }
        int baseOffset = objStartOffset - (int) offset;

        Inflater inflater = new Inflater();
        inflater.setInput(pack, pos, pack.length - pos);
        ByteArrayOutputStream baos = new ByteArrayOutputStream((int) size);
        byte[] buf = new byte[4096];
        while (!inflater.finished()) {
          int count = inflater.inflate(buf);
          if (count > 0) {
            baos.write(buf, 0, count);
          } else {
            break;
          }
        }
        int consumed = (int) inflater.getBytesRead();
        inflater.end();
        pos += consumed;

        deferredDeltas.add(new RawDelta(6, objStartOffset, null, baseOffset, baos.toByteArray()));
      }
    }

    // Resolve deferred deltas
    while (!deferredDeltas.isEmpty()) {
      boolean resolvedAny = false;
      List<RawDelta> remaining = new ArrayList<>();
      for (RawDelta delta : deferredDeltas) {
        GitObject base = null;
        if (delta.type == 7) {
          base = objectsBySha.get(delta.baseSha);
        } else if (delta.type == 6) {
          base = objectsByOffset.get(delta.baseOffset);
        }

        if (base != null) {
          byte[] applied = applyDelta(base.data, delta.deltaData);
          String typeName = base.type;

          byte[] header = (typeName + " " + applied.length + "\0").getBytes(StandardCharsets.UTF_8);
          byte[] fullObj = new byte[header.length + applied.length];
          System.arraycopy(header, 0, fullObj, 0, header.length);
          System.arraycopy(applied, 0, fullObj, header.length, applied.length);

          MessageDigest md = MessageDigest.getInstance("SHA-1");
          byte[] digest = md.digest(fullObj);
          StringBuilder sb = new StringBuilder();
          for (byte d : digest) {
            sb.append(String.format("%02x", d));
          }
          String sha = sb.toString();

          writeObjectTo(gitDir, sha, fullObj);
          GitObject gitObj = new GitObject(typeName, applied, sha);
          objectsBySha.put(sha, gitObj);
          objectsByOffset.put(delta.startOffset, gitObj);
          resolvedAny = true;
        } else {
          remaining.add(delta);
        }
      }
      if (!resolvedAny && !remaining.isEmpty()) {
        throw new RuntimeException("Cyclic or unresolvable packfile deltas");
      }
      deferredDeltas = remaining;
    }
  }

  private static byte[] applyDelta(byte[] base, byte[] delta) {
    int ptr = 0;
    long baseSize = 0;
    int shift = 0;
    while (true) {
      int b = delta[ptr++] & 0xFF;
      baseSize |= (long) (b & 0x7F) << shift;
      shift += 7;
      if ((b & 0x80) == 0) break;
    }

    long targetSize = 0;
    shift = 0;
    while (true) {
      int b = delta[ptr++] & 0xFF;
      targetSize |= (long) (b & 0x7F) << shift;
      shift += 7;
      if ((b & 0x80) == 0) break;
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream((int) targetSize);
    while (ptr < delta.length) {
      int op = delta[ptr++] & 0xFF;
      if ((op & 0x80) != 0) {
        int offset = 0;
        int size = 0;
        if ((op & 0x01) != 0) offset |= (delta[ptr++] & 0xFF);
        if ((op & 0x02) != 0) offset |= ((delta[ptr++] & 0xFF) << 8);
        if ((op & 0x04) != 0) offset |= ((delta[ptr++] & 0xFF) << 16);
        if ((op & 0x08) != 0) offset |= ((delta[ptr++] & 0xFF) << 24);

        if ((op & 0x10) != 0) size |= (delta[ptr++] & 0xFF);
        if ((op & 0x20) != 0) size |= ((delta[ptr++] & 0xFF) << 8);
        if ((op & 0x40) != 0) size |= ((delta[ptr++] & 0xFF) << 16);
        if (size == 0) size = 0x10000;

        out.write(base, offset, size);
      } else if (op > 0) {
        out.write(delta, ptr, op);
        ptr += op;
      }
    }
    return out.toByteArray();
  }

  private static void checkoutTree(File gitDir, File workDir, String treeSha) throws IOException {
    byte[] decompressed = readObjectFrom(gitDir, treeSha);
    int nullIndex = -1;
    for (int i = 0; i < decompressed.length; i++) {
      if (decompressed[i] == 0) {
        nullIndex = i;
        break;
      }
    }
    if (nullIndex == -1) return;
    int ptr = nullIndex + 1;
    while (ptr < decompressed.length) {
      int spaceIdx = -1;
      for (int i = ptr; i < decompressed.length; i++) {
        if (decompressed[i] == ' ') {
          spaceIdx = i;
          break;
        }
      }
      if (spaceIdx == -1) break;
      String mode = new String(decompressed, ptr, spaceIdx - ptr, StandardCharsets.UTF_8);

      int entryNullIdx = -1;
      for (int i = spaceIdx + 1; i < decompressed.length; i++) {
        if (decompressed[i] == 0) {
          entryNullIdx = i;
          break;
        }
      }
      if (entryNullIdx == -1) break;
      String name = new String(decompressed, spaceIdx + 1, entryNullIdx - (spaceIdx + 1), StandardCharsets.UTF_8);

      byte[] shaBytes = new byte[20];
      System.arraycopy(decompressed, entryNullIdx + 1, shaBytes, 0, 20);
      StringBuilder sb = new StringBuilder();
      for (byte b : shaBytes) {
        sb.append(String.format("%02x", b));
      }
      String entrySha = sb.toString();

      ptr = entryNullIdx + 1 + 20;

      File target = new File(workDir, name);
      if (mode.equals("40000") || mode.equals("040000")) {
        target.mkdirs();
        checkoutTree(gitDir, target, entrySha);
      } else {
        byte[] blobObj = readObjectFrom(gitDir, entrySha);
        int blobNull = -1;
        for (int i = 0; i < blobObj.length; i++) {
          if (blobObj[i] == 0) {
            blobNull = i;
            break;
          }
        }
        if (blobNull != -1) {
          byte[] fileContent = Arrays.copyOfRange(blobObj, blobNull + 1, blobObj.length);
          Files.write(target.toPath(), fileContent);
          if (mode.equals("100755")) {
            target.setExecutable(true);
          }
        }
      }
    }
  }

  private static byte[] writeTree(File dir) throws Exception {
    File[] children = dir.listFiles();
    if (children == null) {
      children = new File[0];
    }

    List<TreeEntry> entries = new ArrayList<>();
    for (File child : children) {
      if (child.getName().equals(".git")) {
        continue;
      }
      if (child.isDirectory()) {
        byte[] subTreeSha = writeTree(child);
        entries.add(new TreeEntry("40000", child.getName(), subTreeSha));
      } else if (child.isFile()) {
        byte[] fileBytes = Files.readAllBytes(child.toPath());
        byte[] header = ("blob " + fileBytes.length + "\0").getBytes(StandardCharsets.UTF_8);
        byte[] fullBlob = new byte[header.length + fileBytes.length];
        System.arraycopy(header, 0, fullBlob, 0, header.length);
        System.arraycopy(fileBytes, 0, fullBlob, header.length, fileBytes.length);

        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] blobSha = md.digest(fullBlob);

        StringBuilder sb = new StringBuilder();
        for (byte b : blobSha) {
          sb.append(String.format("%02x", b));
        }
        writeObject(sb.toString(), fullBlob);

        String mode = "100644";
        if (child.canExecute() && !System.getProperty("os.name").toLowerCase().contains("win")) {
          mode = "100755";
        }
        entries.add(new TreeEntry(mode, child.getName(), blobSha));
      }
    }

    entries.sort((e1, e2) -> {
      int len1 = e1.name.length();
      int len2 = e2.name.length();
      int common = Math.min(len1, len2);
      for (int i = 0; i < common; i++) {
        char c1 = e1.name.charAt(i);
        char c2 = e2.name.charAt(i);
        if (c1 != c2) {
          return Character.compare(c1, c2);
        }
      }
      char c1 = (len1 > common) ? e1.name.charAt(common) : (e1.isDir() ? '/' : 0);
      char c2 = (len2 > common) ? e2.name.charAt(common) : (e2.isDir() ? '/' : 0);
      return Character.compare(c1, c2);
    });

    ByteArrayOutputStream treeBuf = new ByteArrayOutputStream();
    for (TreeEntry entry : entries) {
      treeBuf.write(entry.mode.getBytes(StandardCharsets.UTF_8));
      treeBuf.write(' ');
      treeBuf.write(entry.name.getBytes(StandardCharsets.UTF_8));
      treeBuf.write(0);
      treeBuf.write(entry.shaBytes);
    }

    byte[] treeContent = treeBuf.toByteArray();
    byte[] header = ("tree " + treeContent.length + "\0").getBytes(StandardCharsets.UTF_8);
    byte[] fullTree = new byte[header.length + treeContent.length];
    System.arraycopy(header, 0, fullTree, 0, header.length);
    System.arraycopy(treeContent, 0, fullTree, header.length, treeContent.length);

    MessageDigest md = MessageDigest.getInstance("SHA-1");
    byte[] treeSha = md.digest(fullTree);
    StringBuilder sb = new StringBuilder();
    for (byte b : treeSha) {
      sb.append(String.format("%02x", b));
    }
    writeObject(sb.toString(), fullTree);

    return treeSha;
  }

  private static byte[] readObject(String sha) {
    return readObjectFrom(new File(".git"), sha);
  }

  private static byte[] readObjectFrom(File gitDir, String sha) {
    String dir = sha.substring(0, 2);
    String filename = sha.substring(2);
    File objectFile = new File(new File(gitDir, "objects"), dir + "/" + filename);
    try (InputStream in = new InflaterInputStream(new FileInputStream(objectFile));
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[8192];
      int read;
      while ((read = in.read(buf)) != -1) {
        out.write(buf, 0, read);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static void writeObject(String sha, byte[] fullData) {
    writeObjectTo(new File(".git"), sha, fullData);
  }

  private static void writeObjectTo(File gitDir, String sha, byte[] fullData) {
    String dir = sha.substring(0, 2);
    String filename = sha.substring(2);
    File dirFile = new File(new File(gitDir, "objects"), dir);
    dirFile.mkdirs();
    File objectFile = new File(dirFile, filename);
    try (FileOutputStream fos = new FileOutputStream(objectFile);
         DeflaterOutputStream dos = new DeflaterOutputStream(fos)) {
      dos.write(fullData);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}
