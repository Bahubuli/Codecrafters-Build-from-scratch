import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class DnsQuestion {
    public static final int TYPE_A = 1;
    public static final int CLASS_IN = 1;

    private String name;
    private int type;
    private int qClass;

    public DnsQuestion() {
        this("", TYPE_A, CLASS_IN);
    }

    public DnsQuestion(String name) {
        this(name, TYPE_A, CLASS_IN);
    }

    public DnsQuestion(String name, int type, int qClass) {
        this.name = name != null ? name : "";
        this.type = type;
        this.qClass = qClass;
    }

    public static DnsQuestion parse(ByteBuffer buffer) {
        String name = readDomainName(buffer);
        int type = Short.toUnsignedInt(buffer.getShort());
        int qClass = Short.toUnsignedInt(buffer.getShort());
        return new DnsQuestion(name, type, qClass);
    }

    public void write(ByteBuffer buffer) {
        writeDomainName(buffer, this.name);
        buffer.putShort((short) this.type);
        buffer.putShort((short) this.qClass);
    }

    public static String readDomainName(ByteBuffer buffer) {
        StringBuilder sb = new StringBuilder();
        while (buffer.hasRemaining()) {
            int len = Byte.toUnsignedInt(buffer.get());
            if (len == 0) {
                break;
            }
            // Compression pointer check: two most significant bits are 11 (0xC0)
            if ((len & 0xC0) == 0xC0) {
                int pointer = ((len & 0x3F) << 8) | Byte.toUnsignedInt(buffer.get());
                int savedPos = buffer.position();
                buffer.position(pointer);
                String pointerName = readDomainName(buffer);
                buffer.position(savedPos);
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(pointerName);
                return sb.toString();
            }

            byte[] labelBytes = new byte[len];
            buffer.get(labelBytes);
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(new String(labelBytes, StandardCharsets.US_ASCII));
        }
        return sb.toString();
    }

    public static void writeDomainName(ByteBuffer buffer, String name) {
        if (name != null && !name.isEmpty()) {
            String trimmed = name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
            if (!trimmed.isEmpty()) {
                String[] labels = trimmed.split("\\.");
                for (String label : labels) {
                    byte[] labelBytes = label.getBytes(StandardCharsets.US_ASCII);
                    buffer.put((byte) labelBytes.length);
                    buffer.put(labelBytes);
                }
            }
        }
        buffer.put((byte) 0x00);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public int getQClass() {
        return qClass;
    }

    public void setQClass(int qClass) {
        this.qClass = qClass;
    }

    @Override
    public String toString() {
        return "DnsQuestion{" +
                "name='" + name + '\'' +
                ", type=" + type +
                ", qClass=" + qClass +
                '}';
    }
}
