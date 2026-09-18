import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class DnsRecord {
    public static final int TYPE_A = 1;
    public static final int CLASS_IN = 1;

    private String name;
    private int type;
    private int rClass;
    private int ttl;
    private byte[] rdata;

    public DnsRecord() {
        this("", TYPE_A, CLASS_IN, 60, new byte[0]);
    }

    public DnsRecord(String name, int ttl, byte[] rdata) {
        this(name, TYPE_A, CLASS_IN, ttl, rdata);
    }

    public DnsRecord(String name, int type, int rClass, int ttl, byte[] rdata) {
        this.name = name != null ? name : "";
        this.type = type;
        this.rClass = rClass;
        this.ttl = ttl;
        this.rdata = rdata != null ? rdata : new byte[0];
    }

    public static DnsRecord createARecord(String name, int ttl, String ipAddress) {
        try {
            byte[] ipBytes = InetAddress.getByName(ipAddress).getAddress();
            return new DnsRecord(name, TYPE_A, CLASS_IN, ttl, ipBytes);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IPv4 address: " + ipAddress, e);
        }
    }

    public static DnsRecord parse(ByteBuffer buffer) {
        String name = DnsQuestion.readDomainName(buffer);
        int type = Short.toUnsignedInt(buffer.getShort());
        int rClass = Short.toUnsignedInt(buffer.getShort());
        int ttl = buffer.getInt();
        int rdLength = Short.toUnsignedInt(buffer.getShort());
        byte[] rdata = new byte[rdLength];
        buffer.get(rdata);

        return new DnsRecord(name, type, rClass, ttl, rdata);
    }

    public void write(ByteBuffer buffer) {
        DnsQuestion.writeDomainName(buffer, this.name);
        buffer.putShort((short) this.type);
        buffer.putShort((short) this.rClass);
        buffer.putInt(this.ttl);
        buffer.putShort((short) (this.rdata != null ? this.rdata.length : 0));
        if (this.rdata != null && this.rdata.length > 0) {
            buffer.put(this.rdata);
        }
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

    public int getRClass() {
        return rClass;
    }

    public void setRClass(int rClass) {
        this.rClass = rClass;
    }

    public int getTtl() {
        return ttl;
    }

    public void setTtl(int ttl) {
        this.ttl = ttl;
    }

    public byte[] getRdata() {
        return rdata;
    }

    public void setRdata(byte[] rdata) {
        this.rdata = rdata != null ? rdata : new byte[0];
    }

    @Override
    public String toString() {
        return "DnsRecord{" +
                "name='" + name + '\'' +
                ", type=" + type +
                ", rClass=" + rClass +
                ", ttl=" + ttl +
                ", rdLength=" + (rdata != null ? rdata.length : 0) +
                ", rdata=" + Arrays.toString(rdata) +
                '}';
    }
}
