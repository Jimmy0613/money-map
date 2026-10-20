import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneymap.MoneyMapApplication;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;

/** Offline probe: never packaged in the application or exposed as an HTTP endpoint. */
public class StartupProbe {
  public static void main(String[] args) throws Exception {
    if (args.length != 1 || !"local".equals(System.getenv("SPRING_PROFILES_ACTIVE"))) {
      throw new IllegalArgumentException("Use the loopback-only profile-startup.py launcher");
    }
    String jdbc = System.getenv("SPRING_DATASOURCE_URL");
    if (jdbc == null || !jdbc.matches("jdbc:postgresql://127[.]0[.]0[.]1:[0-9]+/fixture_[a-zA-Z0-9_]+")) {
      throw new IllegalArgumentException("Disposable loopback database required");
    }
    Path output = Path.of(args[0]).toAbsolutePath();
    var startup = new BufferingApplicationStartup(8192);
    var app = new SpringApplication(MoneyMapApplication.class);
    app.setApplicationStartup(startup);
    try (var recording = new Recording(Configuration.getConfiguration("profile"))) {
      recording.setName("money-map-isolated-startup");
      recording.setMaxSize(64 * 1024 * 1024);
      recording.setDumpOnExit(false);
      recording.start();
      long started = System.nanoTime();
      try (var context = app.run()) {
        long ready = System.nanoTime();
        recording.stop();
        recording.dump(output.resolve("startup.jfr"));
        var pool = context.getBean(DataSource.class).unwrap(HikariDataSource.class);
        if (pool.getMaximumPoolSize() != 3 || pool.getMinimumIdle() != 0) {
          throw new IllegalStateException("Expected isolated production-like pool settings");
        }
        var timeline = startup.getBufferedTimeline();
        List<Map<String, Object>> events = new ArrayList<>();
        for (var event : timeline.getEvents()) {
          var step = event.getStartupStep();
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("id", step.getId());
          row.put("parent_id", step.getParentId());
          row.put("name", step.getName());
          row.put("start_ns", Duration.between(timeline.getStartTime(), event.getStartTime()).toNanos());
          row.put("end_ns", Duration.between(timeline.getStartTime(), event.getEndTime()).toNanos());
          row.put("duration_ns", event.getDuration().toNanos());
          Map<String, String> tags = new LinkedHashMap<>();
          for (var tag : step.getTags()) {
            // Only static code identifiers; no configuration values or database records.
            if (tag.getKey().equals("beanName") || tag.getKey().equals("beanType")) {
              tags.put(tag.getKey(), tag.getValue());
            }
          }
          row.put("tags", tags);
          events.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("spring_run_seconds", (ready - started) / 1e9);
        result.put("configuration", Map.of(
            "maximum_pool_size", pool.getMaximumPoolSize(),
            "minimum_idle", pool.getMinimumIdle(),
            "connection_timeout_ms", pool.getConnectionTimeout(),
            "idle_timeout_ms", pool.getIdleTimeout(),
            "keepalive_time_ms", pool.getKeepaliveTime(),
            "flyway_separate_datasource", context.getBean(Flyway.class)
                .getConfiguration().getDataSource() != pool));
        result.put("events", events);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
            .writeValue(output.resolve("timeline.json").toFile(), result);
      }
    }
  }
}
