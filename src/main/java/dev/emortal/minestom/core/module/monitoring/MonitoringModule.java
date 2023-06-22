package dev.emortal.minestom.core.module.monitoring;

import com.sun.net.httpserver.HttpServer;
import dev.emortal.api.modules.Module;
import dev.emortal.api.modules.ModuleData;
import dev.emortal.api.modules.ModuleEnvironment;
import dev.emortal.minestom.core.Environment;
import dev.emortal.minestom.core.module.MinestomModule;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.micrometer.prometheus.PrometheusRenameFilter;
import io.pyroscope.http.Format;
import io.pyroscope.javaagent.EventType;
import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.labels.Pyroscope;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;

@ModuleData(name = "monitoring", required = false)
public final class MonitoringModule extends MinestomModule {
    private static final Logger LOGGER = LoggerFactory.getLogger(MonitoringModule.class);
    private static final @NotNull String FLEET_NAME = Objects.requireNonNullElse(System.getenv("FLEET_NAME"), "unknown");

    public MonitoringModule(@NotNull ModuleEnvironment environment) {
        super(environment);
    }

    @Override
    public boolean onLoad() {
        final String envEnabled = System.getenv("MONITORING_ENABLED");
        if (!(Environment.isProduction() || Boolean.parseBoolean(envEnabled))) {
            LOGGER.info("Monitoring is disabled (production: {}, env: {})", Environment.isProduction(), envEnabled);
            return false;
        }

        LOGGER.info("Starting monitoring with: [fleet={}, server={}]", FLEET_NAME, Environment.getHostname());
        final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        registry.config().meterFilter(new PrometheusRenameFilter()).commonTags("fleet", FLEET_NAME);

        if (Environment.isProduction()) registry.config().commonTags("server", Environment.getHostname());

        if (Environment.isProduction()) {
            final String pyroscopeAddress = System.getenv("PYROSCOPE_SERVER_ADDRESS");
            if (pyroscopeAddress == null) {
                LOGGER.warn("PYROSCOPE_SERVER_ADDRESS is not set, Pyroscope will not be enabled");
            } else {
                Pyroscope.setStaticLabels(Map.of(
                                "fleet", FLEET_NAME,
                                "pod", Environment.getHostname()
                        )
                );

                PyroscopeAgent.start(
                        new PyroscopeAgent.Options.Builder(
                                new Config.Builder()
                                        .setApplicationName(FLEET_NAME)
                                        .setProfilingEvent(EventType.ITIMER)
                                        .setFormat(Format.JFR)
                                        .setServerAddress(pyroscopeAddress)
                                        .build()
                        ).build()
                );
            }
        }

        // Java
        new ClassLoaderMetrics().bindTo(registry);
        new JvmGcMetrics().bindTo(registry);
        new JvmMemoryMetrics().bindTo(registry);
        new JvmThreadMetrics().bindTo(registry);
        // Proc
        new ProcessorMetrics().bindTo(registry);
        // Custom
        new MinestomMetrics(eventNode).bindTo(registry);
        new MinestomPacketMetrics(eventNode).bindTo(registry);

        // Add the registry globally so that it can be used by other modules without having to pass it around
        Metrics.addRegistry(registry);

        try {
            LOGGER.info("Starting Prometheus HTTP server on port 8081");
            final HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
            server.createContext("/metrics", exchange -> {
                final String response = registry.scrape();
                exchange.sendResponseHeaders(200, response.getBytes().length);
                try (final OutputStream output = exchange.getResponseBody()) {
                    output.write(response.getBytes());
                }
                exchange.close();
            });

            new Thread(server::start, "micrometer-http").start();
        } catch (final IOException exception) {
            throw new RuntimeException(exception);
        }
        return true;
    }

    @Override
    public void onUnload() {
    }
}
