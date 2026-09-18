import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class Main {
    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        try (DatagramSocket serverSocket = new DatagramSocket(2053)) {
            while (true) {
                final byte[] buf = new byte[512];
                final DatagramPacket packet = new DatagramPacket(buf, buf.length);
                serverSocket.receive(packet);
                System.out.println("Received packet from " + packet.getSocketAddress());

                // Stage 2: Construct 12-byte DNS response header
                DnsHeader responseHeader = new DnsHeader(
                        1234,   // ID: 1234
                        true,   // QR: 1 (Response)
                        0,      // OPCODE: 0 (Standard query)
                        false,  // AA: 0 (Not authoritative)
                        false,  // TC: 0 (Not truncated)
                        false,  // RD: 0 (Recursion not desired)
                        false,  // RA: 0 (Recursion not available)
                        0,      // Z: 0 (Reserved)
                        0,      // RCODE: 0 (No error)
                        0,      // QDCOUNT: 0
                        0,      // ANCOUNT: 0
                        0,      // NSCOUNT: 0
                        0       // ARCOUNT: 0
                );

                DnsMessage responseMessage = new DnsMessage(responseHeader);
                byte[] responseBytes = responseMessage.toBytes();

                final DatagramPacket packetResponse = new DatagramPacket(
                        responseBytes,
                        responseBytes.length,
                        packet.getSocketAddress()
                );
                serverSocket.send(packetResponse);
            }
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }
}
