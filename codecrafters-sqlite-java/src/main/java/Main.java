import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
        try (RandomAccessFile databaseFile = new RandomAccessFile(new File(databaseFilePath), "r")) {
          databaseFile.seek(16);
          int pageSize = databaseFile.readUnsignedShort();
          if (pageSize == 1) {
            pageSize = 65536;
          }

          databaseFile.seek(103);
          int numberOfTables = databaseFile.readUnsignedShort();

          System.out.println("database page size: " + pageSize);
          System.out.println("number of tables: " + numberOfTables);
        } catch (IOException e) {
          System.out.println("Error reading file: " + e.getMessage());
        }
      }
      case ".tables" -> {
        try (RandomAccessFile databaseFile = new RandomAccessFile(new File(databaseFilePath), "r")) {
          databaseFile.seek(16);
          int pageSize = databaseFile.readUnsignedShort();
          if (pageSize == 1) {
            pageSize = 65536;
          }

          byte[] page1 = new byte[pageSize];
          databaseFile.seek(0);
          databaseFile.readFully(page1);
          ByteBuffer pageBuffer = ByteBuffer.wrap(page1);

          // Page 1 B-tree header starts at byte offset 100
          // Offset 103..104: 2-byte number of cells on this page
          int cellCount = Short.toUnsignedInt(pageBuffer.getShort(103));

          // Cell pointer array starts at byte offset 108
          List<String> tableNames = new ArrayList<>();
          for (int i = 0; i < cellCount; i++) {
            int cellOffset = Short.toUnsignedInt(pageBuffer.getShort(108 + i * 2));
            pageBuffer.position(cellOffset);

            // Table B-tree leaf cell format:
            // 1. Payload size (varint)
            // 2. Row ID (varint)
            // 3. Payload (Record format)
            readVarint(pageBuffer);
            readVarint(pageBuffer);

            // Record format:
            // 1. Header size (varint, includes the varint itself)
            int headerStart = pageBuffer.position();
            long headerSize = readVarint(pageBuffer);

            // 2. Serial type code for each column (varint)
            List<Long> serialTypes = new ArrayList<>();
            while (pageBuffer.position() - headerStart < headerSize) {
              serialTypes.add(readVarint(pageBuffer));
            }

            // 3. Record body: values for each column
            // Columns in sqlite_schema:
            // 0: type (text)
            // 1: name (text)
            // 2: tbl_name (text)
            // 3: rootpage (int)
            // 4: sql (text)
            String type = null;
            String tblName = null;

            for (int colIndex = 0; colIndex < serialTypes.size(); colIndex++) {
              int colSize = getSerialTypeSize(serialTypes.get(colIndex));
              byte[] colBytes = new byte[colSize];
              pageBuffer.get(colBytes);

              if (colIndex == 0) {
                type = new String(colBytes, StandardCharsets.UTF_8);
              } else if (colIndex == 2) {
                tblName = new String(colBytes, StandardCharsets.UTF_8);
              }
            }

            if ("table".equalsIgnoreCase(type) && tblName != null && !tblName.startsWith("sqlite_")) {
              tableNames.add(tblName);
            }
          }

          System.out.println(String.join(" ", tableNames));
        } catch (IOException e) {
          System.out.println("Error reading file: " + e.getMessage());
        }
      }
      default -> System.out.println("Missing or invalid command passed: " + command);
    }
  }

  static long readVarint(ByteBuffer buffer) {
    long value = 0;
    for (int i = 0; i < 8; i++) {
      byte b = buffer.get();
      value = (value << 7) | (b & 0x7F);
      if ((b & 0x80) == 0) {
        return value;
      }
    }
    byte b = buffer.get();
    value = (value << 8) | (b & 0xFF);
    return value;
  }

  static int getSerialTypeSize(long serialType) {
    if (serialType >= 12) {
      if (serialType % 2 == 0) {
        return (int) ((serialType - 12) / 2);
      } else {
        return (int) ((serialType - 13) / 2);
      }
    }
    return switch ((int) serialType) {
      case 0, 8, 9, 10, 11 -> 0;
      case 1 -> 1;
      case 2 -> 2;
      case 3 -> 3;
      case 4 -> 4;
      case 5 -> 6;
      case 6, 7 -> 8;
      default -> 0;
    };
  }
}
