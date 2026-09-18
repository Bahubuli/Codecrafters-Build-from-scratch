import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public class Main {
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
            byte[] decompressed = out.toByteArray();
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
          } catch (IOException e) {
            throw new RuntimeException(e);
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
              String dir = sha.substring(0, 2);
              String filename = sha.substring(2);
              File dirFile = new File(".git/objects/" + dir);
              dirFile.mkdirs();
              File objectFile = new File(dirFile, filename);
              try (FileOutputStream fos = new FileOutputStream(objectFile);
                   DeflaterOutputStream dos = new DeflaterOutputStream(fos)) {
                dos.write(fullData);
              }
            }
            System.out.println(sha);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }
      }
      default -> System.out.println("Unknown command: " + command);
    }
  }
}
