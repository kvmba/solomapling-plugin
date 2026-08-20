package soloMapling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BotLogger {
    private static final Logger LOGGER = LoggerFactory.getLogger(BotLogger.class);

    public static void log(String message) {
        LOGGER.info(message);
    }
}
