package go.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Length-delimited, explicitly typed frames; never deserializes network objects. */
public final class Protocol {
    public static final int VERSION = 1, MAX_FRAME = 1_048_576, MAX_STRING = 524_288;

    private Protocol() {}

    public record Frame(String type, long requestId, Map<String, String> fields) {
        public Frame {
            fields = Map.copyOf(fields);
        }

        public Frame(String type, Map<String, String> fields) {
            this(type, 0, fields);
        }

        public String get(String key) {
            return fields.getOrDefault(key, "");
        }

        public int integer(String key) {
            return Integer.parseInt(get(key));
        }

        public long number(String key) {
            return Long.parseLong(get(key));
        }
    }

    public static void write(OutputStream output, Frame frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream body = new DataOutputStream(bytes);
        body.writeShort(VERSION);
        writeString(body, frame.type);
        body.writeLong(frame.requestId);
        if (frame.fields.size() > 64) throw new IOException("Too many fields");
        body.writeInt(frame.fields.size());
        for (var entry : frame.fields.entrySet()) {
            writeString(body, entry.getKey());
            writeString(body, entry.getValue());
        }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_FRAME) throw new IOException("Frame too large");
        DataOutputStream wire = new DataOutputStream(output);
        wire.writeInt(payload.length);
        wire.write(payload);
        wire.flush();
    }

    public static Frame read(InputStream input) throws IOException {
        DataInputStream wire = new DataInputStream(input);
        int length = wire.readInt();
        if (length < 18 || length > MAX_FRAME) throw new IOException("Invalid frame length");
        byte[] payload = new byte[length];
        wire.readFully(payload);
        DataInputStream body = new DataInputStream(new ByteArrayInputStream(payload));
        if (body.readUnsignedShort() != VERSION)
            throw new IOException("Unsupported protocol version");
        String type = readString(body);
        if (type.isEmpty() || type.length() > 40) throw new IOException("Invalid message type");
        long request = body.readLong();
        int count = body.readInt();
        if (count < 0 || count > 64) throw new IOException("Invalid field count");
        Map<String, String> fields = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String key = readString(body), value = readString(body);
            if (fields.put(key, value) != null) throw new IOException("Duplicate field");
        }
        if (body.available() != 0) throw new IOException("Trailing frame bytes");
        return new Frame(type, request, fields);
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING) throw new IOException("String too large");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING || length > in.available())
            throw new IOException("Invalid string length");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
