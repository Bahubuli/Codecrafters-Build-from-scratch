import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
    public static void main(String[] args) {
        System.err.println("Logs from your program will appear here!");

        int port = 9092;
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);

            try (Socket clientSocket = serverSocket.accept()) {
                System.err.println("Client connected!");
                DataInputStream in = new DataInputStream(clientSocket.getInputStream());

                int messageSize = in.readInt();
                short apiKey = in.readShort();
                short apiVersion = in.readShort();
                int correlationId = in.readInt();

                System.err.printf("Request: size=%d, apiKey=%d, apiVersion=%d, correlationId=%d%n",
                        messageSize, apiKey, apiVersion, correlationId);

                int remaining = messageSize - (2 + 2 + 4);
                if (remaining > 0) {
                    in.skipBytes(remaining);
                }

                ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
                DataOutputStream bodyOut = new DataOutputStream(bodyStream);

                short errorCode = (apiVersion >= 0 && apiVersion <= 4) ? (short) 0 : (short) 35;
                bodyOut.writeShort(errorCode);

                if (errorCode == 0) {
                    // api_keys (COMPACT_ARRAY): length + 1 as unsigned varint (1 key -> 2)
                    writeUnsignedVarint(bodyOut, 2);

                    // Entry 1: ApiVersions (key: 18, min: 0, max: 4)
                    bodyOut.writeShort((short) 18);
                    bodyOut.writeShort((short) 0);
                    bodyOut.writeShort((short) 4);
                    bodyOut.writeByte(0); // TAG_BUFFER for api_key entry

                    // throttle_time_ms (INT32)
                    bodyOut.writeInt(0);

                    // TAG_BUFFER for response body
                    bodyOut.writeByte(0);
                }

                byte[] body = bodyStream.toByteArray();
                int responseMessageSize = 4 + body.length; // correlation_id (4B) + body

                DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());
                out.writeInt(responseMessageSize);
                out.writeInt(correlationId);
                out.write(body);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("IOException: " + e.getMessage());
        }
    }

    private static void writeUnsignedVarint(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }
}
