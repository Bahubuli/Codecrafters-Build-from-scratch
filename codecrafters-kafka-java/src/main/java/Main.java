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

                short errorCode = (apiVersion >= 0 && apiVersion <= 4) ? (short) 0 : (short) 35;

                ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
                DataOutputStream bodyOut = new DataOutputStream(bodyStream);
                bodyOut.writeShort(errorCode);
                byte[] body = bodyStream.toByteArray();

                int responseMessageSize = 4 + body.length; // correlation_id (4) + body

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
}
