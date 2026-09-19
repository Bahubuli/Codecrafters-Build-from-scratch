import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Main {
    public static void main(String[] args) {
        System.err.println("Logs from your program will appear here!");

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
            // api_keys (COMPACT_ARRAY): 2 keys -> length + 1 = 3
            writeUnsignedVarint(bodyOut, 3);

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

        // Build DescribeTopicPartitions Response Body v0
        ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
        DataOutputStream bodyOut = new DataOutputStream(bodyStream);

        // throttle_time_ms (INT32)
        bodyOut.writeInt(0);

        // topics (COMPACT_ARRAY)
        writeUnsignedVarint(bodyOut, topicNames.size() + 1);
        for (String topicName : topicNames) {
            // error_code: 3 (UNKNOWN_TOPIC_OR_PARTITION)
            bodyOut.writeShort((short) 3);
            // topic_name: COMPACT_STRING
            writeCompactString(bodyOut, topicName);
            // topic_id: UUID (16 zero bytes)
            bodyOut.write(new byte[16]);
            // is_internal: boolean (false -> 0)
            bodyOut.writeByte(0);
            // partitions: COMPACT_ARRAY (0 elements -> length + 1 = 1)
            writeUnsignedVarint(bodyOut, 1);
            // topic_authorized_operations: INT32 (0)
            bodyOut.writeInt(0);
            // TAG_BUFFER for topic entry
            bodyOut.writeByte(0);
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

    private static void skipTaggedFields(DataInputStream in) throws IOException {
        int numTaggedFields = readUnsignedVarint(in);
        for (int i = 0; i < numTaggedFields; i++) {
            readUnsignedVarint(in); // tag
            int dataLen = readUnsignedVarint(in);
            in.skipBytes(dataLen);
        }
    }
}
