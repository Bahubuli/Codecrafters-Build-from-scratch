import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
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
      default -> System.out.println("Unknown command: " + command);
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

    // Sort entries according to Git's tree ordering
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
    String dir = sha.substring(0, 2);
    String filename = sha.substring(2);
    File objectFile = new File(".git/objects/" + dir + "/" + filename);
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
    String dir = sha.substring(0, 2);
    String filename = sha.substring(2);
    File dirFile = new File(".git/objects/" + dir);
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
