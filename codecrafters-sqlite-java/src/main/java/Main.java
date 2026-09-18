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
        SelectQuery query = parseSelectQuery(trimmedCommand);
        if (query == null) {
          System.out.println("Invalid SQL query: " + trimmedCommand);
          return;
        }

        int pageSize = readPageSize(databaseFile);
        List<SchemaRow> schema = readSchema(databaseFile, pageSize);

        SchemaRow targetTable = null;
        for (SchemaRow row : schema) {
          if ("table".equalsIgnoreCase(row.type) && row.tblName != null && row.tblName.equalsIgnoreCase(query.tableName)) {
            targetTable = row;
            break;
          }
        }

        if (targetTable == null) {
          System.out.println("Table not found: " + query.tableName);
          return;
        }

        // Parse column definitions from CREATE TABLE sql statement
        List<ColumnInfo> columns = parseColumns(targetTable.sql);
        List<Integer> targetColIndices = new ArrayList<>();
        if (!query.isCount) {
          for (String reqCol : query.columns) {
            int targetColIndex = -1;
            for (int i = 0; i < columns.size(); i++) {
              if (columns.get(i).name.equalsIgnoreCase(reqCol)) {
                targetColIndex = i;
                break;
              }
            }

            if (targetColIndex == -1) {
              System.out.println("Column not found: " + reqCol);
              return;
            }
            targetColIndices.add(targetColIndex);
          }
        }

        int whereColIndex = -1;
        if (query.whereColumn != null) {
          for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).name.equalsIgnoreCase(query.whereColumn)) {
              whereColIndex = i;
              break;
            }
          }

          if (whereColIndex == -1) {
            System.out.println("Column not found: " + query.whereColumn);
            return;
          }
        }

        ScanContext ctx = new ScanContext(query, columns, targetColIndices, whereColIndex);
        traverseTableBtree(databaseFile, pageSize, targetTable.rootpage, ctx);

        if (query.isCount) {
          System.out.println(ctx.matchCount);
        }
      } catch (IOException e) {
        System.out.println("Error reading file: " + e.getMessage());
      }
    } else {
      System.out.println("Missing or invalid command passed: " + command);
    }
  }

  static class ScanContext {
    SelectQuery query;
    List<ColumnInfo> columns;
    List<Integer> targetColIndices;
    int whereColIndex;
    int matchCount;

    ScanContext(SelectQuery query, List<ColumnInfo> columns, List<Integer> targetColIndices, int whereColIndex) {
      this.query = query;
      this.columns = columns;
      this.targetColIndices = targetColIndices;
      this.whereColIndex = whereColIndex;
      this.matchCount = 0;
    }
  }

  // Stage 8: Traverse multi-page table B-tree (interior pages 0x05 and leaf pages 0x0D)
  static void traverseTableBtree(RandomAccessFile databaseFile, int pageSize, int pageNumber, ScanContext ctx) throws IOException {
    long pageOffset = (long) (pageNumber - 1) * pageSize;
    byte[] pageData = new byte[pageSize];
    databaseFile.seek(pageOffset);
    databaseFile.readFully(pageData);
    ByteBuffer pageBuffer = ByteBuffer.wrap(pageData);

    int btreeHeaderOffset = (pageNumber == 1) ? 100 : 0;
    int pageType = pageBuffer.get(btreeHeaderOffset) & 0xFF;

    if (pageType == 0x05) {
      // Interior Table B-tree page:
      // Offset 3..4: number of cells (2-byte unsigned short)
      // Offset 8..11: rightmost child page number (4-byte unsigned int)
      // Offset 12..: cell pointer array (2 bytes per cell)
      int cellCount = Short.toUnsignedInt(pageBuffer.getShort(btreeHeaderOffset + 3));
      int rightChildPage = pageBuffer.getInt(btreeHeaderOffset + 8);
      int cellPointerArrayOffset = btreeHeaderOffset + 12;

      for (int i = 0; i < cellCount; i++) {
        int cellOffset = Short.toUnsignedInt(pageBuffer.getShort(cellPointerArrayOffset + i * 2));
        // Interior cell structure:
        // First 4 bytes: left child page number (4-byte unsigned big-endian integer)
        // Following bytes: varint integer key (rowid)
        int leftChildPage = pageBuffer.getInt(cellOffset);
        traverseTableBtree(databaseFile, pageSize, leftChildPage, ctx);
      }
      traverseTableBtree(databaseFile, pageSize, rightChildPage, ctx);
    } else if (pageType == 0x0D) {
      // Leaf Table B-tree page:
      // Offset 3..4: number of cells (2-byte unsigned short)
      // Offset 8..: cell pointer array (2 bytes per cell)
      int cellCount = Short.toUnsignedInt(pageBuffer.getShort(btreeHeaderOffset + 3));
      int cellPointerArrayOffset = btreeHeaderOffset + 8;

      if (ctx.query.isCount && ctx.query.whereColumn == null) {
        ctx.matchCount += cellCount;
        return;
      }

      for (int i = 0; i < cellCount; i++) {
        int cellOffset = Short.toUnsignedInt(pageBuffer.getShort(cellPointerArrayOffset + i * 2));
        pageBuffer.position(cellOffset);

        // Table leaf cell:
        // 1. payload size (varint)
        // 2. rowid (varint)
        // 3. payload (record format)
        readVarint(pageBuffer);
        long rowid = readVarint(pageBuffer);

        // Record format:
        // 1. Header size (varint)
        int headerStart = pageBuffer.position();
        long headerSize = readVarint(pageBuffer);

        // 2. Column serial types (varints)
        List<Long> serialTypes = new ArrayList<>();
        while (pageBuffer.position() - headerStart < headerSize) {
          serialTypes.add(readVarint(pageBuffer));
        }
        pageBuffer.position(headerStart + (int) headerSize);

        // 3. Record body: decode all columns in this record
        int totalCols = Math.max(ctx.columns.size(), serialTypes.size());
        String[] recordValues = new String[totalCols];
        for (int col = 0; col < serialTypes.size(); col++) {
          long st = serialTypes.get(col);
          boolean isPk = (col < ctx.columns.size()) && ctx.columns.get(col).isIntegerPrimaryKey;
          recordValues[col] = readColumnValue(pageBuffer, st, isPk, rowid);
        }
        for (int col = serialTypes.size(); col < ctx.columns.size(); col++) {
          if (ctx.columns.get(col).isIntegerPrimaryKey) {
            recordValues[col] = String.valueOf(rowid);
          }
        }

        // Check WHERE condition
        if (ctx.whereColIndex != -1) {
          String rowVal = recordValues[ctx.whereColIndex];
          if (rowVal == null || !rowVal.equals(ctx.query.whereValue)) {
            continue;
          }
        }

        if (ctx.query.isCount) {
          ctx.matchCount++;
          continue;
        }

        // Build row output according to requested columns order
        List<String> rowValues = new ArrayList<>();
        for (int colIdx : ctx.targetColIndices) {
          String val = recordValues[colIdx];
          rowValues.add(val != null ? val : "");
        }

        System.out.println(String.join("|", rowValues));
      }
    }
  }

  static class SelectQuery {
    List<String> columns;
    String tableName;
    boolean isCount;
    String whereColumn;
    String whereValue;

    SelectQuery(List<String> columns, String tableName, boolean isCount, String whereColumn, String whereValue) {
      this.columns = columns;
      this.tableName = tableName;
      this.isCount = isCount;
      this.whereColumn = whereColumn;
      this.whereValue = whereValue;
    }
  }

  static SelectQuery parseSelectQuery(String sql) {
    Matcher matcher = Pattern.compile("(?is)^SELECT\\s+(.+?)\\s+FROM\\s+((?:\"[^\"]+\"|'[^']+'|`[^`]+`|\\[[^\\]]+\\]|[^\\s;]+))(?:\\s+WHERE\\s+(.+))?\\s*;?$").matcher(sql.trim());
    if (!matcher.find()) {
      return null;
    }
    String selectExpr = matcher.group(1).trim();
    String tableName = matcher.group(2).trim().replaceAll("[\"'\\\\\\[\\\\\\]`]", "");
    boolean isCount = selectExpr.replaceAll("\\s+", "").equalsIgnoreCase("count(*)");

    String whereColumn = null;
    String whereValue = null;
    String whereClause = matcher.group(3);
    if (whereClause != null) {
      whereClause = whereClause.trim();
      if (whereClause.endsWith(";")) {
        whereClause = whereClause.substring(0, whereClause.length() - 1).trim();
      }
      int eqIndex = whereClause.indexOf("==");
      int opLen = 2;
      if (eqIndex == -1) {
        eqIndex = whereClause.indexOf('=');
        opLen = 1;
      }
      if (eqIndex != -1) {
        whereColumn = whereClause.substring(0, eqIndex).trim();
        whereValue = whereClause.substring(eqIndex + opLen).trim();

        whereColumn = whereColumn.replaceAll("[\"'\\\\\\[\\\\\\]`]", "").trim();
        if (whereColumn.contains(".")) {
          whereColumn = whereColumn.substring(whereColumn.lastIndexOf('.') + 1).trim();
        }

        if (whereValue.length() >= 2) {
          if ((whereValue.startsWith("'") && whereValue.endsWith("'")) ||
              (whereValue.startsWith("\"") && whereValue.endsWith("\""))) {
            whereValue = whereValue.substring(1, whereValue.length() - 1);
          }
        }
        whereValue = whereValue.replace("''", "'");
      }
    }

    if (isCount) {
      return new SelectQuery(List.of(), tableName, true, whereColumn, whereValue);
    }
    String[] parts = selectExpr.split(",");
    List<String> columns = new ArrayList<>();
    for (String part : parts) {
      String col = part.replaceAll("[\"'\\\\\\[\\\\\\]`]", "").trim();
      if (col.contains(".")) {
        col = col.substring(col.lastIndexOf('.') + 1).trim();
      }
      if (!col.isEmpty()) {
        columns.add(col);
      }
    }
    return new SelectQuery(columns, tableName, false, whereColumn, whereValue);
  }

  static class ColumnInfo {
    String name;
    boolean isIntegerPrimaryKey;

    ColumnInfo(String name, boolean isIntegerPrimaryKey) {
      this.name = name;
      this.isIntegerPrimaryKey = isIntegerPrimaryKey;
    }
  }

  static List<ColumnInfo> parseColumns(String createTableSql) {
    List<ColumnInfo> columns = new ArrayList<>();
    if (createTableSql == null) return columns;

    int firstParen = createTableSql.indexOf('(');
    int lastParen = createTableSql.lastIndexOf(')');
    if (firstParen == -1 || lastParen == -1 || firstParen >= lastParen) {
      return columns;
    }

    String inside = createTableSql.substring(firstParen + 1, lastParen);
    List<String> colDefs = new ArrayList<>();
    int depth = 0;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < inside.length(); i++) {
      char c = inside.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') depth--;
      if (c == ',' && depth == 0) {
        colDefs.add(sb.toString().trim());
        sb.setLength(0);
      } else {
        sb.append(c);
      }
    }
    if (sb.length() > 0) {
      colDefs.add(sb.toString().trim());
    }

    for (String def : colDefs) {
      String trimmed = def.trim();
      if (trimmed.isEmpty()) continue;

      String colName;
      String rest;
      if (trimmed.startsWith("\"")) {
        int close = trimmed.indexOf('"', 1);
        if (close != -1) {
          colName = trimmed.substring(1, close);
          rest = trimmed.substring(close + 1).trim();
        } else {
          colName = trimmed;
          rest = "";
        }
      } else if (trimmed.startsWith("[")) {
        int close = trimmed.indexOf(']', 1);
        if (close != -1) {
          colName = trimmed.substring(1, close);
          rest = trimmed.substring(close + 1).trim();
        } else {
          colName = trimmed;
          rest = "";
        }
      } else if (trimmed.startsWith("`")) {
        int close = trimmed.indexOf('`', 1);
        if (close != -1) {
          colName = trimmed.substring(1, close);
          rest = trimmed.substring(close + 1).trim();
        } else {
          colName = trimmed;
          rest = "";
        }
      } else if (trimmed.startsWith("'")) {
        int close = trimmed.indexOf('\'', 1);
        if (close != -1) {
          colName = trimmed.substring(1, close);
          rest = trimmed.substring(close + 1).trim();
        } else {
          colName = trimmed;
          rest = "";
        }
      } else {
        String[] parts = trimmed.split("\\s+", 2);
        colName = parts[0];
        rest = parts.length > 1 ? parts[1].trim() : "";
      }

      colName = colName.replaceAll("[\"'\\\\\\[\\\\\\]`]", "").trim();

      String upperCol = colName.toUpperCase();
      String upperRest = rest.toUpperCase();
      if (upperCol.equals("CONSTRAINT")) continue;
      if (upperCol.equals("PRIMARY") && upperRest.startsWith("KEY")) continue;
      if (upperCol.equals("FOREIGN") && upperRest.startsWith("KEY")) continue;
      if (upperCol.equals("CHECK") && upperRest.startsWith("(")) continue;
      if (upperCol.equals("UNIQUE") && upperRest.startsWith("(")) continue;

      boolean isPk = Pattern.compile("(?i)\\binteger\\s+primary\\s+key\\b").matcher(trimmed).find();
      columns.add(new ColumnInfo(colName, isPk));
    }

    return columns;
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

  static String readColumnValue(ByteBuffer buffer, long serialType, boolean isIntegerPrimaryKey, long rowid) {
    if (serialType == 0) {
      return isIntegerPrimaryKey ? String.valueOf(rowid) : null;
    }
    if (serialType == 1) {
      return String.valueOf((long) buffer.get());
    }
    if (serialType == 2) {
      return String.valueOf((long) buffer.getShort());
    }
    if (serialType == 3) {
      int b0 = buffer.get();
      int b1 = buffer.get() & 0xFF;
      int b2 = buffer.get() & 0xFF;
      return String.valueOf((long) ((b0 << 16) | (b1 << 8) | b2));
    }
    if (serialType == 4) {
      return String.valueOf((long) buffer.getInt());
    }
    if (serialType == 5) {
      long b0 = buffer.get();
      long b1 = buffer.get() & 0xFFL;
      long b2 = buffer.get() & 0xFFL;
      long b3 = buffer.get() & 0xFFL;
      long b4 = buffer.get() & 0xFFL;
      long b5 = buffer.get() & 0xFFL;
      return String.valueOf((b0 << 40) | (b1 << 32) | (b2 << 24) | (b3 << 16) | (b4 << 8) | b5);
    }
    if (serialType == 6) {
      return String.valueOf(buffer.getLong());
    }
    if (serialType == 7) {
      return String.valueOf(buffer.getDouble());
    }
    if (serialType == 8) {
      return "0";
    }
    if (serialType == 9) {
      return "1";
    }
    if (serialType >= 12 && serialType % 2 == 0) {
      int size = (int) ((serialType - 12) / 2);
      byte[] bytes = new byte[size];
      buffer.get(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    }
    if (serialType >= 13 && serialType % 2 != 0) {
      int size = (int) ((serialType - 13) / 2);
      byte[] bytes = new byte[size];
      buffer.get(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    }
    return null;
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
