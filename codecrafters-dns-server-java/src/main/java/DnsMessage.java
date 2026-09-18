import java.nio.ByteBuffer;

public class DnsMessage {
    private DnsHeader header;

    public DnsMessage() {
    }

    public DnsMessage(DnsHeader header) {
        this.header = header;
    }

    public static DnsMessage parse(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        DnsHeader header = DnsHeader.parse(buffer);
        return new DnsMessage(header);
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(512);
        if (header != null) {
            header.write(buffer);
        }
        buffer.flip();
        byte[] result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    public DnsHeader getHeader() {
        return header;
    }

    public void setHeader(DnsHeader header) {
        this.header = header;
    }
}
