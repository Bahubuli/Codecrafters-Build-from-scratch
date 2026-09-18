import java.nio.ByteBuffer;

public class DnsHeader {
    public static final int HEADER_SIZE = 12;

    private int id;
    private boolean qr;      // 0 for query, 1 for response
    private int opcode;      // 4 bits
    private boolean aa;      // 1 bit: Authoritative Answer
    private boolean tc;      // 1 bit: Truncation
    private boolean rd;      // 1 bit: Recursion Desired
    private boolean ra;      // 1 bit: Recursion Available
    private int z;           // 3 bits: Reserved
    private int rcode;       // 4 bits: Response Code
    private int qdCount;     // 16 bits: Question Count
    private int anCount;     // 16 bits: Answer Count
    private int nsCount;     // 16 bits: Authority Record Count
    private int arCount;     // 16 bits: Additional Record Count

    public DnsHeader() {
    }

    public DnsHeader(int id, boolean qr, int opcode, boolean aa, boolean tc, boolean rd,
                     boolean ra, int z, int rcode, int qdCount, int anCount, int nsCount, int arCount) {
        this.id = id;
        this.qr = qr;
        this.opcode = opcode;
        this.aa = aa;
        this.tc = tc;
        this.rd = rd;
        this.ra = ra;
        this.z = z;
        this.rcode = rcode;
        this.qdCount = qdCount;
        this.anCount = anCount;
        this.nsCount = nsCount;
        this.arCount = arCount;
    }

    public static DnsHeader parse(ByteBuffer buffer) {
        if (buffer.remaining() < HEADER_SIZE) {
            throw new IllegalArgumentException("Buffer underflow: expected at least 12 bytes for DNS header");
        }
        int id = Short.toUnsignedInt(buffer.getShort());
        int flags = Short.toUnsignedInt(buffer.getShort());

        boolean qr = ((flags >> 15) & 0x01) == 1;
        int opcode = (flags >> 11) & 0x0F;
        boolean aa = ((flags >> 10) & 0x01) == 1;
        boolean tc = ((flags >> 9) & 0x01) == 1;
        boolean rd = ((flags >> 8) & 0x01) == 1;
        boolean ra = ((flags >> 7) & 0x01) == 1;
        int z = (flags >> 4) & 0x07;
        int rcode = flags & 0x0F;

        int qdCount = Short.toUnsignedInt(buffer.getShort());
        int anCount = Short.toUnsignedInt(buffer.getShort());
        int nsCount = Short.toUnsignedInt(buffer.getShort());
        int arCount = Short.toUnsignedInt(buffer.getShort());

        return new DnsHeader(id, qr, opcode, aa, tc, rd, ra, z, rcode, qdCount, anCount, nsCount, arCount);
    }

    public void write(ByteBuffer buffer) {
        buffer.putShort((short) id);

        int flags = 0;
        if (qr) flags |= (1 << 15);
        flags |= ((opcode & 0x0F) << 11);
        if (aa) flags |= (1 << 10);
        if (tc) flags |= (1 << 9);
        if (rd) flags |= (1 << 8);
        if (ra) flags |= (1 << 7);
        flags |= ((z & 0x07) << 4);
        flags |= (rcode & 0x0F);

        buffer.putShort((short) flags);
        buffer.putShort((short) qdCount);
        buffer.putShort((short) anCount);
        buffer.putShort((short) nsCount);
        buffer.putShort((short) arCount);
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(HEADER_SIZE);
        write(buffer);
        return buffer.array();
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public boolean isQr() { return qr; }
    public void setQr(boolean qr) { this.qr = qr; }

    public int getOpcode() { return opcode; }
    public void setOpcode(int opcode) { this.opcode = opcode; }

    public boolean isAa() { return aa; }
    public void setAa(boolean aa) { this.aa = aa; }

    public boolean isTc() { return tc; }
    public void setTc(boolean tc) { this.tc = tc; }

    public boolean isRd() { return rd; }
    public void setRd(boolean rd) { this.rd = rd; }

    public boolean isRa() { return ra; }
    public void setRa(boolean ra) { this.ra = ra; }

    public int getZ() { return z; }
    public void setZ(int z) { this.z = z; }

    public int getRcode() { return rcode; }
    public void setRcode(int rcode) { this.rcode = rcode; }

    public int getQdCount() { return qdCount; }
    public void setQdCount(int qdCount) { this.qdCount = qdCount; }

    public int getAnCount() { return anCount; }
    public void setAnCount(int anCount) { this.anCount = anCount; }

    public int getNsCount() { return nsCount; }
    public void setNsCount(int nsCount) { this.nsCount = nsCount; }

    public int getArCount() { return arCount; }
    public void setArCount(int arCount) { this.arCount = arCount; }
}
