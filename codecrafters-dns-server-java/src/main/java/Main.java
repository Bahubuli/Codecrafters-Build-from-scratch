import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;

public class Main {
    public static void main(String[] args) {
        System.out.println("Logs from your program will appear here!");

        String resolverArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--resolver".equals(args[i]) && i + 1 < args.length) {
                resolverArg = args[i + 1];
                break;
            }
        }

        InetSocketAddress resolverAddress = null;
        if (resolverArg != null) {
            String[] parts = resolverArg.split(":");
            try {
                InetAddress resolverHost = InetAddress.getByName(parts[0]);
                int resolverPort = Integer.parseInt(parts[1]);
                resolverAddress = new InetSocketAddress(resolverHost, resolverPort);
                System.out.println("Configured resolver: " + resolverAddress);
            } catch (Exception e) {
                System.err.println("Failed to parse resolver address: " + resolverArg);
            }
        }

        try (DatagramSocket serverSocket = new DatagramSocket(2053);
             DatagramSocket forwardSocket = new DatagramSocket()) {
            forwardSocket.setSoTimeout(5000);

            while (true) {
                final byte[] buf = new byte[1024];
                final DatagramPacket packet = new DatagramPacket(buf, buf.length);
                serverSocket.receive(packet);
                System.out.println("Received packet from " + packet.getSocketAddress());

                // Stage 8: Forwarding DNS server implementation
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

                        if (resolverAddress != null) {
                            // Forward each question individually to the upstream resolver
                            try {
                                DnsHeader forwardReqHeader = new DnsHeader(
                                        reqHeader.getId(),
                                        false,  // QR: 0 (Query)
                                        reqHeader.getOpcode(),
                                        false,
                                        false,
                                        reqHeader.isRd(),
                                        false,
                                        0,
                                        0,
                                        1,      // QDCOUNT: 1
                                        0,
                                        0,
                                        0
                                );
                                DnsMessage forwardReqMessage = new DnsMessage(forwardReqHeader);
                                forwardReqMessage.addQuestion(question);
                                byte[] forwardReqBytes = forwardReqMessage.toBytes();

                                DatagramPacket forwardPacket = new DatagramPacket(
                                        forwardReqBytes,
                                        forwardReqBytes.length,
                                        resolverAddress
                                );
                                forwardSocket.send(forwardPacket);

                                byte[] respBuf = new byte[2048];
                                DatagramPacket forwardRespPacket = new DatagramPacket(respBuf, respBuf.length);
                                forwardSocket.receive(forwardRespPacket);

                                byte[] respData = Arrays.copyOfRange(forwardRespPacket.getData(), 0, forwardRespPacket.getLength());
                                DnsMessage forwardRespMessage = DnsMessage.parse(respData);

                                if (forwardRespMessage.getAnswers() != null) {
                                    for (DnsRecord record : forwardRespMessage.getAnswers()) {
                                        responseMessage.addAnswer(record);
                                    }
                                }
                            } catch (Exception e) {
                                System.err.println("Error forwarding DNS query: " + e.getMessage());
                            }
                        } else {
                            // Fallback for stages 1-7 without resolver
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
