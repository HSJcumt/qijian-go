package go.model;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Server-side configuration only; never packaged with the client. */
public final class ServiceConfig {
    private ServiceConfig() {}

    public static Properties load(String file) throws IOException {
        Properties result = new Properties();
        Path path = Path.of(file);
        if (Files.exists(path))
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                result.load(reader);
            }
        return result;
    }

}
