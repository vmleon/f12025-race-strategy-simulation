package dev.victormartin.telemetry;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    private static final long BACKOFF_INITIAL_MS = 2_000;
    private static final long BACKOFF_MAX_MS = 60_000;

    private final DataSource dataSource;
    private final Map<String, Long> backoffMs = new ConcurrentHashMap<>();

    public QueueService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void enqueue(String queueName, String jsonPayload) {
        String sql = """
                DECLARE
                  props  DBMS_AQ.MESSAGE_PROPERTIES_T;
                  msgid  RAW(16);
                  payload JSON := JSON(:1);
                BEGIN
                  DBMS_AQ.ENQUEUE(
                    queue_name         => :2,
                    enqueue_options    => DBMS_AQ.ENQUEUE_OPTIONS_T(),
                    message_properties => props,
                    payload            => payload,
                    msgid              => msgid
                  );
                END;
                """;
        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(sql)) {
            conn.setAutoCommit(false);
            cs.setString(1, jsonPayload);
            cs.setString(2, queueName);
            cs.execute();
            conn.commit();
            log.info("Enqueued {} payloadBytes={}", queueName, jsonPayload.length());
        } catch (Exception e) {
            log.error("QueueService: enqueue to {} failed: {}", queueName, e.getMessage(), e);
        }
    }

    public String dequeue(String queueName, int waitSeconds) {
        return dequeue(queueName, null, waitSeconds);
    }

    public String dequeue(String queueName, String subscriberName, int waitSeconds) {
        String subscriberClause = subscriberName != null
                ? "deq_opts.consumer_name := '" + subscriberName + "';\n  "
                : "";
        String sql = """
                DECLARE
                  deq_opts DBMS_AQ.DEQUEUE_OPTIONS_T;
                  props    DBMS_AQ.MESSAGE_PROPERTIES_T;
                  msgid    RAW(16);
                  payload  JSON;
                BEGIN
                  deq_opts.wait := :1;
                  %sDEQ_OPTS.navigation := DBMS_AQ.FIRST_MESSAGE;
                  DBMS_AQ.DEQUEUE(
                    queue_name         => :2,
                    dequeue_options    => deq_opts,
                    message_properties => props,
                    payload            => payload,
                    msgid              => msgid
                  );
                  :3 := JSON_SERIALIZE(payload);
                END;
                """.formatted(subscriberClause);
        try (Connection conn = dataSource.getConnection();
             CallableStatement cs = conn.prepareCall(sql)) {
            conn.setAutoCommit(false);
            cs.setInt(1, waitSeconds);
            cs.setString(2, queueName);
            cs.registerOutParameter(3, Types.VARCHAR);
            cs.execute();
            conn.commit();
            backoffMs.remove(queueName);
            return cs.getString(3);
        } catch (Exception e) {
            // ORA-25228: timeout — no message available
            if (e.getMessage() != null && e.getMessage().contains("25228")) {
                backoffMs.remove(queueName);
                return null;
            }
            long wait = backoffMs.merge(queueName, BACKOFF_INITIAL_MS,
                    (prev, init) -> Math.min(prev * 2, BACKOFF_MAX_MS));
            log.warn("QueueService: dequeue from {} failed: {} (backing off {}ms)", queueName, e.getMessage(), wait);
            try {
                Thread.sleep(wait);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
