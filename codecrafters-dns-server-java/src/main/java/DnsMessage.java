import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class DnsMessage {
    private DnsHeader header;
    private List<DnsQuestion> questions = new ArrayList<>();
    private List<DnsRecord> answers = new ArrayList<>();

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

    public DnsMessage(DnsHeader header, List<DnsQuestion> questions, List<DnsRecord> answers) {
        this.header = header;
        if (questions != null) {
            this.questions = new ArrayList<>(questions);
        }
        if (answers != null) {
            this.answers = new ArrayList<>(answers);
        }
    }

    public static DnsMessage parse(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        DnsHeader header = DnsHeader.parse(buffer);
        DnsMessage message = new DnsMessage(header);

        for (int i = 0; i < header.getQdCount() && buffer.hasRemaining(); i++) {
            message.addQuestion(DnsQuestion.parse(buffer));
        }

        for (int i = 0; i < header.getAnCount() && buffer.hasRemaining(); i++) {
            message.addAnswer(DnsRecord.parse(buffer));
        }

        return message;
    }

    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(512);
        if (header != null) {
            header.setQdCount(questions != null ? questions.size() : 0);
            header.setAnCount(answers != null ? answers.size() : 0);
            header.write(buffer);
        }
        if (questions != null) {
            for (DnsQuestion question : questions) {
                question.write(buffer);
            }
        }
        if (answers != null) {
            for (DnsRecord answer : answers) {
                answer.write(buffer);
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

    public List<DnsRecord> getAnswers() {
        return answers;
    }

    public void setAnswers(List<DnsRecord> answers) {
        this.answers = (answers != null) ? new ArrayList<>(answers) : new ArrayList<>();
    }

    public void addAnswer(DnsRecord answer) {
        if (answer != null) {
            this.answers.add(answer);
        }
    }
}
