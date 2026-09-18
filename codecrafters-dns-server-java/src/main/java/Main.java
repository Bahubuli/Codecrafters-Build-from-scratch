import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;

public class Main {
    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        try (DatagramSocket serverSocket = new DatagramSocket(2053)) {
            while (true) {
                final byte[] buf = new byte[512];
                final DatagramPacket packet = new DatagramPacket(buf, buf.length);
                serverSocket.receive(packet);
                System.out.println("Received packet from " + packet.getSocketAddress());

                // Stage 6: Parse request header and question sections, reflect query parameters
                byte[] requestBytes = Arrays.copyOfRange(packet.getData(), 0, packet.getLength());
                DnsMessage requestMessage = DnsMessage.parse(requestBytes);
                DnsHeader reqHeader = requestMessage.getHeader();

                int rcode = (reqHeader.getOpcode() == 0) ? 0 : 4;

                DnsHeader responseHeader = new DnsHeader(
                        reqHeader.getId(),      // Mimic the query ID
                        true,                   // QR: 1 (Response)
                        reqHeader.getOpcode(),  // Mimic query OPCODE
                        false,                  // AA: 0 (Not authoritative)
                        false,                  // TC: 0 (Not truncated)
                        reqHeader.isRd(),       // Mimic query RD
                        false,                  // RA: 0 (Recursion not available)
                        0,                      // Z: 0 (Reserved)
                        rcode,                  // RCODE: 0 if OPCODE is 0, else 4
                        0,                      // QDCOUNT (updated by DnsMessage.toBytes())
                        0,                      // ANCOUNT (updated by DnsMessage.toBytes())
                        0,                      // NSCOUNT: 0
                        0                       // ARCOUNT: 0
                );

                DnsMessage responseMessage = new DnsMessage(responseHeader);

                if (requestMessage.getQuestions() != null && !requestMessage.getQuestions().isEmpty()) {
                    for (DnsQuestion question : requestMessage.getQuestions()) {
                        responseMessage.addQuestion(question);
                        if (reqHeader.getOpcode() == 0) {
                            responseMessage.addAnswer(new DnsRecord(
                                    question.getName(),
                                    1,  // TYPE A
                                    1,  // CLASS IN
                                    60, // TTL
                                    new byte[] {8, 8, 8, 8}
                            ));
                        }
                    }
                }

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
