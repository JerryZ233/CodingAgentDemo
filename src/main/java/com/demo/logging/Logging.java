package com.demo.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.ErrorHandler;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.Configurator;

/** Process-wide file logging, initialized before application configuration is read. */
public final class Logging {
    private static boolean initialized;
    private static Path file;
    private static final AtomicBoolean failed = new AtomicBoolean();
    private static final AtomicBoolean notified = new AtomicBoolean();
    private static volatile Runnable failureNotice;
    private Logging() { }

    public static synchronized void initialize() {
        if (initialized) return;
        String directory = setting("coding.agent.log.dir", "CODING_AGENT_LOG_DIR",
                Path.of(System.getProperty("user.home"), ".coding-agent", "logs").toString());
        String level = setting("coding.agent.log.level", "CODING_AGENT_LOG_LEVEL", "DEBUG");
        configure(Path.of(directory), Level.toLevel(level.toUpperCase(java.util.Locale.ROOT), Level.DEBUG), "10 MB");
    }
    static String setting(String property, String environment, String fallback) {
        return choose(System.getProperties().getProperty(property), System.getenv(environment), fallback);
    }
    static String choose(String property, String environment, String fallback) {
        if (property != null && !property.isBlank()) return property;
        if (environment != null && !environment.isBlank()) return environment;
        return fallback;
    }

    /** Package-private entry for deterministic file/rotation tests. */
    static synchronized void configure(Path directory, Level level, String size) {
        try {
            Files.createDirectories(directory);
            String stamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSSSSSSSS"));
            String base = "agent-" + stamp + "-" + ProcessHandle.current().pid();
            Path target = directory.toAbsolutePath().resolve(base + ".log");
            Files.createFile(target);
            var builder = ConfigurationBuilderFactory.newConfigurationBuilder();
            builder.setConfigurationName("coding-agent-file").setStatusLevel(Level.OFF);
            var rollover = builder.newComponent("DefaultRolloverStrategy").addAttribute("max", 10)
                    .addComponent(builder.newComponent("Delete").addAttribute("basePath", directory.toAbsolutePath().toString())
                            .addAttribute("maxDepth", 1)
                            .addComponent(builder.newComponent("IfFileName").addAttribute("glob", "agent-*-*.log.gz")
                                    .addComponent(builder.newComponent("IfLastModified").addAttribute("age", "14d"))));
            builder.add(builder.newAppender("file", "RollingFile")
                    .addAttribute("fileName", target.toString())
                    .addAttribute("filePattern", directory.toAbsolutePath().resolve(base + "-%i.log.gz").toString())
                    .addAttribute("ignoreExceptions", true)
                    .add(builder.newLayout("PatternLayout").addAttribute("charset", "UTF-8")
                            .addAttribute("pattern", "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%t] %c session_id=%X{session_id} run_id=%X{run_id} iteration=%X{iteration} tool_call_id=%X{tool_call_id} %msg%n"))
                    .addComponent(builder.newComponent("Policies")
                            .addComponent(builder.newComponent("SizeBasedTriggeringPolicy").addAttribute("size", size)))
                    .addComponent(rollover));
            builder.add(builder.newLogger("com.demo", level).addAttribute("additivity", false)
                    .add(builder.newAppenderRef("file")));
            builder.add(builder.newRootLogger(Level.WARN).add(builder.newAppenderRef("file")));
            var configuration = builder.build();
            var appender = configuration.getAppender("file");
            if (appender == null) throw new IOException("File appender could not be created");
            appender.setHandler(new ErrorHandler() {
                public void error(String message) { reportFailure(); }
                public void error(String message, Throwable error) { reportFailure(); }
                public void error(String message, LogEvent event, Throwable error) { reportFailure(); }
            });
            Configurator.reconfigure(configuration);
            if (!appender.isStarted()) throw new IOException("File appender did not start");
            file = target;
            initialized = true;
            failed.set(false);
            notified.set(false);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed to initialize file logging", e);
        }
    }
    public static Logger logger(Class<?> type) { initialize(); return LogManager.getLogger(type); }
    public static Path file() { initialize(); return file; }
    public static void onFailure(Runnable notice) { failureNotice = notice; notifyFailure(); }
    static void reportFailure() { failed.set(true); notifyFailure(); }
    private static void notifyFailure() {
        Runnable notice = failureNotice;
        if (failed.get() && notice != null && notified.compareAndSet(false, true)) {
            try { notice.run(); } catch (RuntimeException ignored) { /* Logging cannot stop a task. */ }
        }
    }
}
