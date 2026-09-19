import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    private static volatile String serverPropertiesPath = null;

    static class TopicInfo {
        final String name;
        final byte[] topicId;
        final List<PartitionInfo> partitions = new ArrayList<>();

        TopicInfo(String name, byte[] topicId) {
            this.name = name;
            this.topicId = topicId;
        }
    }

    static class PartitionInfo {
        final int partitionId;
        final int leader;
        final int leaderEpoch;
        final List<Integer> replicas;
        final List<Integer> isr;

        PartitionInfo(int partitionId, int leader, int leaderEpoch, List<Integer> replicas, List<Integer> isr) {
            this.partitionId = partitionId;
            this.leader = leader;
            this.leaderEpoch = leaderEpoch;
            this.replicas = replicas;
            this.isr = isr;
        }
    }

    public static void main(String[] args) {
        System.err.println("Logs from your program will appear here!");
        if (args.length > 0) {
            serverPropertiesPath = args[0];
        }

        int port = 9092;
        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.submit(() -> handleClient(clientSocket));
            }
        } catch (IOException e) {
            System.err.println("IOException: " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (clientSocket;
             DataInputStream in = new DataInputStream(clientSocket.getInputStream());
             DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream())) {

            while (true) {
                int messageSize;
                try {
                    messageSize = in.readInt();
                } catch (EOFException e) {
                    // Normal client disconnection
                    break;
                }

                short apiKey = in.readShort();
                short apiVersion = in.readShort();
                int correlationId = in.readInt();

                System.err.printf("Request: size=%d, apiKey=%d, apiVersion=%d, correlationId=%d%n",
                        messageSize, apiKey, apiVersion, correlationId);

                if (apiKey == 18) {
                    // ApiVersions
                    handleApiVersions(in, out, messageSize, apiVersion, correlationId);
                } else if (apiKey == 75) {
                    // DescribeTopicPartitions
                    handleDescribeTopicPartitions(in, out, messageSize, apiVersion, correlationId);
                } else {
                    int remaining = messageSize - (2 + 2 + 4);
                    if (remaining > 0) {
                        in.skipBytes(remaining);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Client handler exception: " + e.getMessage());
        }
    }

    private static void handleApiVersions(DataInputStream in, DataOutputStream out,
                                          int messageSize, short apiVersion, int correlationId) throws IOException {
        int remaining = messageSize - (2 + 2 + 4);
        if (remaining > 0) {
            in.skipBytes(remaining);
        }

        ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
        DataOutputStream bodyOut = new DataOutputStream(bodyStream);

        short errorCode = (apiVersion >= 0 && apiVersion <= 4) ? (short) 0 : (short) 35;
        bodyOut.writeShort(errorCode);

        if (errorCode == 0) {
            // api_keys (COMPACT_ARRAY): 3 keys -> length + 1 = 4
            writeUnsignedVarint(bodyOut, 4);

            // Entry 1: ApiVersions (key: 18, min: 0, max: 4)
            bodyOut.writeShort((short) 18);
            bodyOut.writeShort((short) 0);
            bodyOut.writeShort((short) 4);
            bodyOut.writeByte(0); // TAG_BUFFER

            // Entry 2: DescribeTopicPartitions (key: 75, min: 0, max: 0)
            bodyOut.writeShort((short) 75);
            bodyOut.writeShort((short) 0);
            bodyOut.writeShort((short) 0);
            bodyOut.writeByte(0); // TAG_BUFFER

            // Entry 3: Fetch (key: 1, min: 0, max: 16)
            bodyOut.writeShort((short) 1);
            bodyOut.writeShort((short) 0);
            bodyOut.writeShort((short) 16);
            bodyOut.writeByte(0); // TAG_BUFFER

            // throttle_time_ms (INT32)
            bodyOut.writeInt(0);

            // TAG_BUFFER for response body
            bodyOut.writeByte(0);
        }

        byte[] body = bodyStream.toByteArray();
        int responseMessageSize = 4 + body.length; // Response Header v0: correlation_id (4B) + body

        out.writeInt(responseMessageSize);
        out.writeInt(correlationId);
        out.write(body);
        out.flush();
    }

    private static void handleDescribeTopicPartitions(DataInputStream in, DataOutputStream out,
                                                      int messageSize, short apiVersion, int correlationId) throws IOException {
        // Request Header v2 parsing: client_id + tagged fields
        short clientIdLen = in.readShort();
        if (clientIdLen > 0) {
            in.skipBytes(clientIdLen);
        }
        skipTaggedFields(in);

        // DescribeTopicPartitions Request Body v0
        int topicsArrayLen = readUnsignedVarint(in);
        int numTopics = topicsArrayLen > 0 ? topicsArrayLen - 1 : 0;
        List<String> topicNames = new ArrayList<>();
        for (int i = 0; i < numTopics; i++) {
            String topicName = readCompactString(in);
            topicNames.add(topicName);
            skipTaggedFields(in);
        }
        int responsePartitionLimit = in.readInt();
        byte cursor = in.readByte();
        skipTaggedFields(in);

        // Sort topic names alphabetically as required by CodeCrafters specification
        Collections.sort(topicNames);

        // Load cluster metadata from disk
        Map<String, TopicInfo> topicsMap = loadClusterMetadata();

        // Build DescribeTopicPartitions Response Body v0
        ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
        DataOutputStream bodyOut = new DataOutputStream(bodyStream);

        // throttle_time_ms (INT32)
        bodyOut.writeInt(0);

        // topics (COMPACT_ARRAY)
        writeUnsignedVarint(bodyOut, topicNames.size() + 1);
        for (String topicName : topicNames) {
            TopicInfo topic = topicsMap.get(topicName);
            if (topic == null) {
                // error_code: 3 (UNKNOWN_TOPIC_OR_PARTITION)
                bodyOut.writeShort((short) 3);
                // topic_name: COMPACT_STRING
                writeCompactString(bodyOut, topicName);
                // topic_id: UUID (16 zero bytes)
                bodyOut.write(new byte[16]);
                // is_internal: boolean (false -> 0)
                bodyOut.writeByte(0);
                // partitions: COMPACT_ARRAY (0 elements -> 1)
                writeUnsignedVarint(bodyOut, 1);
                // topic_authorized_operations: INT32 (0)
                bodyOut.writeInt(0);
                // TAG_BUFFER for topic entry
                bodyOut.writeByte(0);
            } else {
                // error_code: 0 (NO_ERROR)
                bodyOut.writeShort((short) 0);
                // topic_name: COMPACT_STRING
                writeCompactString(bodyOut, topic.name);
                // topic_id: UUID (16 bytes)
                bodyOut.write(topic.topicId);
                // is_internal: boolean (false -> 0)
                bodyOut.writeByte(0);

                // Sort partitions ascending by partitionId
                topic.partitions.sort(Comparator.comparingInt(p -> p.partitionId));

                // partitions: COMPACT_ARRAY
                writeUnsignedVarint(bodyOut, topic.partitions.size() + 1);
                for (PartitionInfo p : topic.partitions) {
                    bodyOut.writeShort((short) 0); // partition error_code: 0
                    bodyOut.writeInt(p.partitionId);
                    bodyOut.writeInt(p.leader);
                    bodyOut.writeInt(p.leaderEpoch);

                    // replica_nodes
                    writeUnsignedVarint(bodyOut, p.replicas.size() + 1);
                    for (int r : p.replicas) {
                        bodyOut.writeInt(r);
                    }

                    // isr_nodes
                    writeUnsignedVarint(bodyOut, p.isr.size() + 1);
                    for (int r : p.isr) {
                        bodyOut.writeInt(r);
                    }

                    // eligible_leader_replicas (empty -> 1)
                    writeUnsignedVarint(bodyOut, 1);
                    // last_known_elr (empty -> 1)
                    writeUnsignedVarint(bodyOut, 1);
                    // offline_replicas (empty -> 1)
                    writeUnsignedVarint(bodyOut, 1);

                    // TAG_BUFFER for partition entry
                    bodyOut.writeByte(0);
                }
                // topic_authorized_operations: INT32 (0)
                bodyOut.writeInt(0);
                // TAG_BUFFER for topic entry
                bodyOut.writeByte(0);
            }
        }

        // next_cursor: -1 (0xFF)
        bodyOut.writeByte((byte) -1);

        // TAG_BUFFER for response body
        bodyOut.writeByte(0);

        byte[] body = bodyStream.toByteArray();

        // Response Header v1: correlation_id (4B) + TAG_BUFFER (1B)
        int responseMessageSize = 4 + 1 + body.length;

        out.writeInt(responseMessageSize);
        out.writeInt(correlationId);
        out.writeByte(0); // Response Header v1 TAG_BUFFER
        out.write(body);
        out.flush();
    }

    private static Map<String, TopicInfo> loadClusterMetadata() {
        Map<String, TopicInfo> topicsMap = new HashMap<>();
        Map<ByteBuffer, TopicInfo> topicByIdMap = new HashMap<>();

        String logDir = "/tmp/kraft-combined-logs";
        if (serverPropertiesPath != null) {
            File propsFile = new File(serverPropertiesPath);
            if (propsFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(propsFile))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("log.dirs=")) {
                            logDir = line.substring("log.dirs=".length()).trim();
                        } else if (line.startsWith("log.dir=")) {
                            logDir = line.substring("log.dir=".length()).trim();
                        }
                    }
                } catch (IOException ignored) {}
            }
        }

        File metadataLog = new File(logDir, "__cluster_metadata-0/00000000000000000000.log");
        if (!metadataLog.exists()) {
            return topicsMap;
        }

        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(metadataLog)))) {
            while (in.available() > 0) {
                long baseOffset = in.readLong();
                int batchLength = in.readInt();
                byte[] batchBytes = new byte[batchLength];
                in.readFully(batchBytes);

                ByteBuffer buf = ByteBuffer.wrap(batchBytes);
                int partitionLeaderEpoch = buf.getInt();
                byte magic = buf.get();
                int crc = buf.getInt();
                short attributes = buf.getShort();
                int lastOffsetDelta = buf.getInt();
                long baseTimestamp = buf.getLong();
                long maxTimestamp = buf.getLong();
                long producerId = buf.getLong();
                short producerEpoch = buf.getShort();
                int baseSequence = buf.getInt();
                int recordsCount = buf.getInt();

                for (int r = 0; r < recordsCount; r++) {
                    int recordLength = readVarint(buf);
                    byte recordAttributes = buf.get();
                    long timestampDelta = readVarlong(buf);
                    int offsetDelta = readVarint(buf);

                    int keyLength = readVarint(buf);
                    if (keyLength > 0) {
                        buf.position(buf.position() + keyLength);
                    }

                    int valueLength = readVarint(buf);
                    if (valueLength > 0) {
                        byte[] valBytes = new byte[valueLength];
                        buf.get(valBytes);
                        parseMetadataRecord(ByteBuffer.wrap(valBytes), topicsMap, topicByIdMap);
                    }

                    int headersCount = readUnsignedVarint(buf);
                    for (int h = 0; h < headersCount; h++) {
                        int headerKeyLen = readVarint(buf);
                        if (headerKeyLen > 0) buf.position(buf.position() + headerKeyLen);
                        int headerValLen = readVarint(buf);
                        if (headerValLen > 0) buf.position(buf.position() + headerValLen);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error reading cluster metadata: " + e.getMessage());
        }

        return topicsMap;
    }

    private static void parseMetadataRecord(ByteBuffer buf,
                                            Map<String, TopicInfo> topicsMap,
                                            Map<ByteBuffer, TopicInfo> topicByIdMap) {
        try {
            int frameVersion = readUnsignedVarint(buf);
            int apiKey = readUnsignedVarint(buf);
            int version = readUnsignedVarint(buf);

            if (apiKey == 2) {
                // TopicRecord
                String topicName = readCompactString(buf);
                byte[] topicId = new byte[16];
                buf.get(topicId);
                int tags = readUnsignedVarint(buf);

                TopicInfo ti = new TopicInfo(topicName, topicId);
                topicsMap.put(topicName, ti);
                topicByIdMap.put(ByteBuffer.wrap(topicId), ti);
            } else if (apiKey == 3) {
                // PartitionRecord
                int partitionId = buf.getInt();
                byte[] topicId = new byte[16];
                buf.get(topicId);

                int replicasCount = readUnsignedVarint(buf) - 1;
                List<Integer> replicas = new ArrayList<>();
                for (int i = 0; i < replicasCount; i++) {
                    replicas.add(buf.getInt());
                }

                int isrCount = readUnsignedVarint(buf) - 1;
                List<Integer> isr = new ArrayList<>();
                for (int i = 0; i < isrCount; i++) {
                    isr.add(buf.getInt());
                }

                int removingReplicasCount = readUnsignedVarint(buf);
                if (removingReplicasCount > 0) {
                    for (int i = 0; i < removingReplicasCount - 1; i++) buf.getInt();
                }

                int addingReplicasCount = readUnsignedVarint(buf);
                if (addingReplicasCount > 0) {
                    for (int i = 0; i < addingReplicasCount - 1; i++) buf.getInt();
                }

                int leader = buf.getInt();
                int leaderEpoch = buf.getInt();
                int partitionEpoch = buf.getInt();

                PartitionInfo pi = new PartitionInfo(partitionId, leader, leaderEpoch, replicas, isr);
                TopicInfo ti = topicByIdMap.get(ByteBuffer.wrap(topicId));
                if (ti != null) {
                    ti.partitions.add(pi);
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing metadata record: " + e.getMessage());
        }
    }

    private static void writeUnsignedVarint(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }

    private static int readUnsignedVarint(DataInputStream in) throws IOException {
        int value = 0;
        int i = 0;
        int b;
        while (((b = in.readByte()) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
            if (i > 28) {
                throw new IllegalArgumentException("Varint too long");
            }
        }
        value |= b << i;
        return value;
    }

    private static int readUnsignedVarint(ByteBuffer buf) {
        int value = 0;
        int i = 0;
        int b;
        while (((b = buf.get() & 0xFF) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
            if (i > 28) throw new IllegalArgumentException("Varint too long");
        }
        value |= b << i;
        return value;
    }

    private static int readVarint(ByteBuffer buf) {
        int value = readUnsignedVarint(buf);
        return (value >>> 1) ^ -(value & 1);
    }

    private static long readVarlong(ByteBuffer buf) {
        long value = 0;
        int i = 0;
        long b;
        while (((b = buf.get() & 0xFF) & 0x80) != 0) {
            value |= (b & 0x7F) << i;
            i += 7;
            if (i > 63) throw new IllegalArgumentException("Varlong too long");
        }
        value |= b << i;
        return (value >>> 1) ^ -(value & 1);
    }

    private static void writeCompactString(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            writeUnsignedVarint(out, 0);
            return;
        }
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeUnsignedVarint(out, bytes.length + 1);
        out.write(bytes);
    }

    private static String readCompactString(DataInputStream in) throws IOException {
        int len = readUnsignedVarint(in);
        if (len <= 0) return null;
        int strLen = len - 1;
        byte[] bytes = new byte[strLen];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String readCompactString(ByteBuffer buf) {
        int len = readUnsignedVarint(buf);
        if (len <= 0) return null;
        int strLen = len - 1;
        byte[] bytes = new byte[strLen];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void skipTaggedFields(DataInputStream in) throws IOException {
        int numTaggedFields = readUnsignedVarint(in);
        for (int i = 0; i < numTaggedFields; i++) {
            readUnsignedVarint(in); // tag
            int dataLen = readUnsignedVarint(in);
            in.skipBytes(dataLen);
        }
    }
}
