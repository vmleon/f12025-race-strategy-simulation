package udp.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class LogFormatTest {

    private static final Pattern LINE = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} " +
                    "\\[(INFO |WARN |ERROR|DEBUG|TRACE)\\] " +
                    "\\[telemetry   \\] " +
                    "\\[sess=\\S{1,10} *\\] " +
                    ".+$"
    );

    @Test
    void consoleAppenderEmitsUnifiedFormat() {
        LoggerContext ctx = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);

        ListAppender<ILoggingEvent> list = new ListAppender<>();
        list.setContext(ctx);
        list.start();
        root.addAppender(list);

        MDC.put("sessionUid", "42");
        try {
            LoggerFactory.getLogger(LogFormatTest.class).info("hello");
        } finally {
            MDC.remove("sessionUid");
            root.detachAppender(list);
        }

        ch.qos.logback.core.ConsoleAppender<ILoggingEvent> console =
                (ch.qos.logback.core.ConsoleAppender<ILoggingEvent>) root.getAppender("CONSOLE");
        ch.qos.logback.classic.encoder.PatternLayoutEncoder enc =
                (ch.qos.logback.classic.encoder.PatternLayoutEncoder) console.getEncoder();
        String rendered = enc.getLayout().doLayout(list.list.get(0));

        assertTrue(LINE.matcher(rendered.trim()).matches(),
                "line did not match unified format: " + rendered);
    }
}
