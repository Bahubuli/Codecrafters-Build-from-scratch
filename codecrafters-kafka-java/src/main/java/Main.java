import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
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

                out.writeInt(responseMessageSize);
                out.writeInt(correlationId);
                out.write(body);
                out.flush();
            }
        } catch (IOException e) {
            System.err.println("Client handler exception: " + e.getMessage());
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
