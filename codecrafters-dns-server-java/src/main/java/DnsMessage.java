import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class DnsMessage {
    private DnsHeader header;
    private List<DnsQuestion> questions = new ArrayList<>();

    public DnsMessage() {
    }

    public DnsMessage(DnsHeader header) {
        this.header = header;
    }

    public DnsMessage(DnsHeader header, List<DnsQuestion> questions) {
        this.header = header;
        if (questions != null) {
            this.questions = new ArrayList<>(questions);
        }
    }

    public static DnsMessage parse(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        DnsHeader header = DnsHeader.parse(buffer);
        DnsMessage message = new DnsMessage(header);

        for (int i = 0; i < header.getQdCount(); i++) {
            message.addQuestion(DnsQuestion.parse(buffer));
        }

        return message;
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(512);
        if (header != null) {
            header.setQdCount(questions != null ? questions.size() : 0);
            header.write(buffer);
        }
        if (questions != null) {
            for (DnsQuestion question : questions) {
                question.write(buffer);
            }
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

    public List<DnsQuestion> getQuestions() {
        return questions;
    }

    public void setQuestions(List<DnsQuestion> questions) {
        this.questions = (questions != null) ? new ArrayList<>(questions) : new ArrayList<>();
    }

    public void addQuestion(DnsQuestion question) {
        if (question != null) {
            this.questions.add(question);
        }
    }
}
