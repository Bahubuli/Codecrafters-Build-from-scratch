import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class Main {
  public static void main(String[] args) {
    if (args.length < 2) {
      System.out.println("Missing <database path> and <command>");
      return;
    }

    String databaseFilePath = args[0];
    String command = args[1];

    switch (command) {
      case ".dbinfo" -> {
        try (FileInputStream databaseFile = new FileInputStream(new File(databaseFilePath))) {
          // SQLite database header is 100 bytes.
          // For page 1, the B-tree page header begins immediately at offset 100.
          // B-tree page header format:
          //   Offset 0 (File offset 100): 1 byte page type (0x0d = leaf table b-tree page)
          //   Offset 1..2 (File offset 101..102): 2-byte first freeblock offset
          //   Offset 3..4 (File offset 103..104): 2-byte number of cells on this page
          // Read first 108 bytes to cover both the database header and page header.
          byte[] header = databaseFile.readNBytes(108);
          if (header.length < 105) {
            System.err.println("File too small to contain valid SQLite database and page header");
            return;
          }

          int pageSize = Short.toUnsignedInt(ByteBuffer.wrap(header, 16, 2).getShort());
          if (pageSize == 1) {
            pageSize = 65536;
          }

          int numberOfTables = Short.toUnsignedInt(ByteBuffer.wrap(header, 103, 2).getShort());

          System.out.println("database page size: " + pageSize);
          System.out.println("number of tables: " + numberOfTables);
        } catch (IOException e) {
          System.out.println("Error reading file: " + e.getMessage());
        }
      }
      default -> System.out.println("Missing or invalid command passed: " + command);
    }
  }
}
