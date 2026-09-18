import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {
  public static void main(String[] args) {
    if (args.length < 2) {
      System.out.println("Missing <database path> and <command>");
      return;
    }

    String databaseFilePath = args[0];
    String command = args[1];

    String trimmedCommand = command.trim();

    if (trimmedCommand.equalsIgnoreCase(".dbinfo")) {
      try (RandomAccessFile databaseFile = new RandomAccessFile(new File(databaseFilePath), "r")) {
        int pageSize = readPageSize(databaseFile);

        databaseFile.seek(103);
        int numberOfTables = databaseFile.readUnsignedShort();

        System.out.println("database page size: " + pageSize);
        System.out.println("number of tables: " + numberOfTables);
      } catch (IOException e) {
        System.out.println("Error reading file: " + e.getMessage());
      }
    } else if (trimmedCommand.equalsIgnoreCase(".tables")) {
      try (RandomAccessFile databaseFile = new RandomAccessFile(new File(databaseFilePath), "r")) {
        int pageSize = readPageSize(databaseFile);
        List<SchemaRow> schema = readSchema(databaseFile, pageSize);

        List<String> tableNames = new ArrayList<>();
        for (SchemaRow row : schema) {
          if ("table".equalsIgnoreCase(row.type) && row.tblName != null && !row.tblName.startsWith("sqlite_")) {
            tableNames.add(row.tblName);
          }
        }

        System.out.println(String.join(" ", tableNames));
      } catch (IOException e) {
        System.out.println("Error reading file: " + e.getMessage());
      }
    } else if (trimmedCommand.toUpperCase().startsWith("SELECT")) {
      try (RandomAccessFile databaseFile = new RandomAccessFile(new File(databaseFilePath), "r")) {
        String tableName = extractTableName(trimmedCommand);
        if (tableName == null || tableName.isEmpty()) {
          System.out.println("Invalid SQL query: could not extract table name");
          return;
        }

        int pageSize = readPageSize(databaseFile);
        List<SchemaRow> schema = readSchema(databaseFile, pageSize);

        SchemaRow targetTable = null;
        for (SchemaRow row : schema) {
          if ("table".equalsIgnoreCase(row.type) && row.tblName != null && row.tblName.equalsIgnoreCase(tableName)) {
            targetTable = row;
            break;
          }
        }

        if (targetTable == null) {
          System.out.println("Table not found: " + tableName);
          return;
        }

        int rootpage = targetTable.rootpage;
        long pageOffset = (long) (rootpage - 1) * pageSize;
        int btreeHeaderOffset = (rootpage == 1) ? 100 : 0;

        databaseFile.seek(pageOffset + btreeHeaderOffset + 3);
        int rowCount = databaseFile.readUnsignedShort();

        System.out.println(rowCount);
      } catch (IOException e) {
        System.out.println("Error reading file: " + e.getMessage());
      }
    } else {
      System.out.println("Missing or invalid command passed: " + command);
    }
  }

  static String extractTableName(String sql) {
    Matcher matcher = Pattern.compile("(?i)^select\\s+count\\s*\\(.*\\)\\s+from\\s+(\\S+)", Pattern.CASE_INSENSITIVE).matcher(sql);
    if (matcher.find()) {
      return matcher.group(1).replaceAll("[;\"'`]", "");
    }
    String[] parts = sql.split("\\s+");
    return parts[parts.length - 1].replaceAll("[;\"'`]", "");
  }

  static int readPageSize(RandomAccessFile databaseFile) throws IOException {
    databaseFile.seek(16);
    int pageSize = databaseFile.readUnsignedShort();
    if (pageSize == 1) {
      pageSize = 65536;
    }
    return pageSize;
  }

  static class SchemaRow {
    String type;
    String name;
    String tblName;
    int rootpage;
    String sql;

    SchemaRow(String type, String name, String tblName, int rootpage, String sql) {
      this.type = type;
      this.name = name;
      this.tblName = tblName;
      this.rootpage = rootpage;
      this.sql = sql;
    }
  }

  static List<SchemaRow> readSchema(RandomAccessFile databaseFile, int pageSize) throws IOException {
    byte[] page1 = new byte[pageSize];
    databaseFile.seek(0);
    databaseFile.readFully(page1);
    ByteBuffer pageBuffer = ByteBuffer.wrap(page1);

    // Page 1 B-tree header starts at byte offset 100
    // Offset 103..104: 2-byte number of cells on this page
    int cellCount = Short.toUnsignedInt(pageBuffer.getShort(103));

    // Cell pointer array starts at byte offset 108
    List<SchemaRow> rows = new ArrayList<>();
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
      String name = null;
      String tblName = null;
      int rootpage = -1;
      String sql = null;

      for (int colIndex = 0; colIndex < serialTypes.size(); colIndex++) {
        long st = serialTypes.get(colIndex);
        if (colIndex == 0) {
          type = readString(pageBuffer, st);
        } else if (colIndex == 1) {
          name = readString(pageBuffer, st);
        } else if (colIndex == 2) {
          tblName = readString(pageBuffer, st);
        } else if (colIndex == 3) {
          rootpage = (int) readInteger(pageBuffer, st);
        } else if (colIndex == 4) {
          sql = readString(pageBuffer, st);
        } else {
          pageBuffer.position(pageBuffer.position() + getSerialTypeSize(st));
        }
      }

      rows.add(new SchemaRow(type, name, tblName, rootpage, sql));
    }

    return rows;
  }

  static String readString(ByteBuffer buffer, long serialType) {
    if (serialType == 0) {
      return null;
    }
    int size = getSerialTypeSize(serialType);
    byte[] bytes = new byte[size];
    buffer.get(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  static long readInteger(ByteBuffer buffer, long serialType) {
    return switch ((int) serialType) {
      case 1 -> (long) buffer.get();
      case 2 -> (long) buffer.getShort();
      case 3 -> {
        int b0 = buffer.get();
        int b1 = buffer.get() & 0xFF;
        int b2 = buffer.get() & 0xFF;
        yield (long) ((b0 << 16) | (b1 << 8) | b2);
      }
      case 4 -> (long) buffer.getInt();
      case 5 -> {
        long b0 = buffer.get();
        long b1 = buffer.get() & 0xFFL;
        long b2 = buffer.get() & 0xFFL;
        long b3 = buffer.get() & 0xFFL;
        long b4 = buffer.get() & 0xFFL;
        long b5 = buffer.get() & 0xFFL;
        yield (b0 << 40) | (b1 << 32) | (b2 << 24) | (b3 << 16) | (b4 << 8) | b5;
      }
      case 6 -> buffer.getLong();
      case 8 -> 0L;
      case 9 -> 1L;
      default -> 0L;
    };
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
