/*
 * Copyright 2017 VMware, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.elastic;

import io.micrometer.api.instrument.*;
import io.micrometer.api.instrument.distribution.HistogramSnapshot;
import io.micrometer.api.instrument.step.StepMeterRegistry;
import io.micrometer.api.instrument.util.MeterPartition;
import io.micrometer.api.instrument.util.NamedThreadFactory;
import io.micrometer.api.instrument.util.StringUtils;
import io.micrometer.api.ipc.http.HttpSender;
import io.micrometer.api.ipc.http.HttpUrlConnectionSender;
import io.micrometer.api.lang.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.micrometer.api.instrument.util.StringEscapeUtils.escapeJson;
import static java.util.stream.Collectors.joining;

/**
 * {@link MeterRegistry} for Elasticsearch.
 *
 * @author Nicolas Portmann
 * @author Jon Schneider
 * @author Johnny Lim
 * @since 1.1.0
 * @implNote This implementation requires Elasticsearch 7 or above.
 */
public class ElasticMeterRegistry extends StepMeterRegistry {
    private static final ThreadFactory DEFAULT_THREAD_FACTORY = new NamedThreadFactory("elastic-metrics-publisher");
    static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final String ES_METRICS_TEMPLATE = "/_template/metrics_template";

    private static final String TEMPLATE_PROPERTIES = "\"properties\": {\n" +
            "  \"name\": {\n" +
            "    \"type\": \"keyword\"\n" +
            "  },\n" +
            "  \"count\": {\n" +
            "    \"type\": \"double\",\n" +
            "    \"index\": false\n" +
            "  },\n" +
            "  \"value\": {\n" +
            "    \"type\": \"double\",\n" +
            "    \"index\": false\n" +
            "  },\n" +
            "  \"sum\": {\n" +
            "    \"type\": \"double\",\n" +
            "    \"index\": false\n" +
            "  },\n" +
            "  \"mean\": {\n" +
            "    \"type\": \"double\",\n" +
            "    \"index\": false\n" +
            "  },\n" +
            "  \"duration\": {\n" +
            "    \"type\": \"double\",\n" +
            "    \"index\": false\n" +
            "  },\n" +
            "  \"max\": {\n" +
            "    \"type\": \"double\",\n" +
            "    \"index\": false\n" +
            "  },\n" +
            "  \"total\": {\n" +
            "    \"type\": \"double\",\n" +
            "    \"index\": false\n" +
            "  },\n" +
            "  \"unknown\": {\n" +
            "    \"type\": \"double\",\n" +
            "    \"index\": false\n" +
            "  },\n" +
            "  \"active\": {\n" +
            "    \"type\": \"double\",\n" +
            "    \"index\": false\n" +
            "  }\n" +
            "}";
    private static final Function<String, String> TEMPLATE_BODY_AFTER_VERSION_7 = (indexPrefix) -> "{\n" +
            "  \"index_patterns\": [\"" + indexPrefix + "*\"],\n" +
            "  \"mappings\": {\n" +
            "    \"_source\": {\n" +
            "      \"enabled\": false\n" +
            "    },\n" + TEMPLATE_PROPERTIES +
            "  }\n" +
            "}";

    private static final Pattern MAJOR_VERSION_PATTERN = Pattern.compile("\"number\" *: *\"([\\d]+)");

    private static final String ERROR_RESPONSE_BODY_SIGNATURE = "\"errors\":true";
    private static final Pattern STATUS_CREATED_PATTERN = Pattern.compile("\"status\":201");

    private final Logger logger = LoggerFactory.getLogger(ElasticMeterRegistry.class);

    private final ElasticConfig config;
    private final HttpSender httpClient;

    private final DateTimeFormatter indexDateFormatter;

    private final String indexLine;

    private volatile boolean checkedForIndexTemplate;

    @SuppressWarnings("deprecation")
    public ElasticMeterRegistry(ElasticConfig config, Clock clock) {
        this(config, clock, DEFAULT_THREAD_FACTORY,
                new HttpUrlConnectionSender(config.connectTimeout(), config.readTimeout()));
    }

    /**
     * Create a new instance with given parameters.
     *
     * @param config configuration to use
     * @param clock clock to use
     * @param threadFactory thread factory to use
     * @param httpClient http client to use
     * @since 1.2.1
     */
    protected ElasticMeterRegistry(ElasticConfig config, Clock clock, ThreadFactory threadFactory, HttpSender httpClient) {
        super(config, clock);
        config().namingConvention(new ElasticNamingConvention());
        this.config = config;
        indexDateFormatter = DateTimeFormatter.ofPattern(config.indexDateFormat());
        this.httpClient = httpClient;
        if (StringUtils.isNotEmpty(config.pipeline())) {
            indexLine = "{ \"index\" : {\"pipeline\":\"" + config.pipeline() + "\"} }\n";
        } else {
            indexLine = "{ \"index\" : {} }\n";
        }

        start(threadFactory);
    }

    public static Builder builder(ElasticConfig config) {
        return new Builder(config);
    }

    private void createIndexTemplateIfNeeded() {
        if (checkedForIndexTemplate || !config.autoCreateIndex()) {
            return;
        }

        try {
            String uri = config.host() + ES_METRICS_TEMPLATE;
            if (connect(HttpSender.Method.HEAD, uri)
                    .send()
                    .onError(response -> {
                        if (response.code() != 404) {
                            logger.error("could not create index in elastic (HTTP {}): {}", response.code(), response.body());
                        }
                    })
                    .isSuccessful()) {
                checkedForIndexTemplate = true;
                logger.debug("metrics template already exists");
                return;
            }

            connect(HttpSender.Method.PUT, uri)
                    .withJsonContent(getTemplateBody())
                    .send()
                    .onError(response -> logger.error("failed to add metrics template to elastic: {}", response.body()));
        } catch (Throwable e) {
            logger.error("could not create index in elastic", e);
            return;
        }

        checkedForIndexTemplate = true;
    }

    private String getTemplateBody() {
        return TEMPLATE_BODY_AFTER_VERSION_7.apply(config.index() + config.indexDateSeparator());
    }

    private HttpSender.Request.Builder connect(HttpSender.Method method, String uri) {
        return authentication(this.httpClient.newRequest(uri).withMethod(method));
    }

    private HttpSender.Request.Builder authentication(HttpSender.Request.Builder request) {
        if (StringUtils.isNotBlank(config.apiKeyCredentials())) {
            return request.withAuthentication("ApiKey", config.apiKeyCredentials());
        } else {
            return request.withBasicAuthentication(config.userName(), config.password());
        }
    }


    @Override
    protected void publish() {
        createIndexTemplateIfNeeded();

        String uri = config.host() + "/" + indexName() + "/_bulk";
        for (List<Meter> batch : MeterPartition.partition(this, config.batchSize())) {
            try {
                String requestBody = batch.stream()
                        .map(m -> m.match(
                                this::writeGauge,
                                this::writeCounter,
                                this::writeTimer,
                                this::writeSummary,
                                this::writeLongTaskTimer,
                                this::writeTimeGauge,
                                this::writeFunctionCounter,
                                this::writeFunctionTimer,
                                this::writeMeter))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .collect(joining("\n", "", "\n"));
                connect(HttpSender.Method.POST, uri)
                        .withJsonContent(requestBody)
                        .send()
                        .onSuccess(response -> {
                            int numberOfSentItems = batch.size();
                            String responseBody = response.body();
                            if (responseBody.contains(ERROR_RESPONSE_BODY_SIGNATURE)) {
                                int numberOfCreatedItems = countCreatedItems(responseBody);
                                logger.debug("failed metrics payload: {}", requestBody);
                                logger.error("failed to send metrics to elastic (sent {} metrics but created {} metrics): {}",
                                        numberOfSentItems, numberOfCreatedItems, responseBody);
                            } else {
                                logger.debug("successfully sent {} metrics to elastic", numberOfSentItems);
                            }
                        })
                        .onError(response -> {
                            logger.debug("failed metrics payload: {}", requestBody);
                            logger.error("failed to send metrics to elastic: {}", response.body());
                        });
            } catch (Throwable e) {
                logger.error("failed to send metrics to elastic", e);
            }
        }
    }

    // VisibleForTesting
    static int getMajorVersion(String responseBody) {
        Matcher matcher = MAJOR_VERSION_PATTERN.matcher(responseBody);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Unexpected response body: " + responseBody);
        }
        return Integer.parseInt(matcher.group(1));
    }

    // VisibleForTesting
    static int countCreatedItems(String responseBody) {
        Matcher matcher = STATUS_CREATED_PATTERN.matcher(responseBody);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Return index name.
     *
     * @return index name.
     * @since 1.2.0
     */
    protected String indexName() {
        ZonedDateTime dt = ZonedDateTime.ofInstant(new Date(config().clock().wallTime()).toInstant(), ZoneOffset.UTC);
        return config.index() + config.indexDateSeparator() + indexDateFormatter.format(dt);
    }

    // VisibleForTesting
    Optional<String> writeCounter(Counter counter) {
        return writeCounter(counter, counter.count());
    }

    // VisibleForTesting
    Optional<String> writeFunctionCounter(FunctionCounter counter) {
        return writeCounter(counter, counter.count());
    }

    private Optional<String> writeCounter(Meter meter, double value) {
        if (Double.isFinite(value)) {
            return Optional.of(writeDocument(meter, builder -> {
                builder.append(",\"count\":").append(value);
            }));
        }
        return Optional.empty();
    }

    // VisibleForTesting
    Optional<String> writeGauge(Gauge gauge) {
        double value = gauge.value();
        if (Double.isFinite(value)) {
            return Optional.of(writeDocument(gauge, builder -> {
                builder.append(",\"value\":").append(value);
            }));
        }
        return Optional.empty();
    }

    // VisibleForTesting
    Optional<String> writeTimeGauge(TimeGauge gauge) {
        double value = gauge.value(getBaseTimeUnit());
        if (Double.isFinite(value)) {
            return Optional.of(writeDocument(gauge, builder -> {
                builder.append(",\"value\":").append(value);
            }));
        }
        return Optional.empty();
    }

    // VisibleForTesting
    Optional<String> writeFunctionTimer(FunctionTimer timer) {
        double sum = timer.totalTime(getBaseTimeUnit());
        double mean = timer.mean(getBaseTimeUnit());
        if (Double.isFinite(sum) && Double.isFinite(mean)) {
            return Optional.of(writeDocument(timer, builder -> {
                builder.append(",\"count\":").append(timer.count());
                builder.append(",\"sum\":").append(sum);
                builder.append(",\"mean\":").append(mean);
            }));
        }
        return Optional.empty();
    }

    // VisibleForTesting
    Optional<String> writeLongTaskTimer(LongTaskTimer timer) {
        return Optional.of(writeDocument(timer, builder -> {
            builder.append(",\"activeTasks\":").append(timer.activeTasks());
            builder.append(",\"duration\":").append(timer.duration(getBaseTimeUnit()));
        }));
    }

    // VisibleForTesting
    Optional<String> writeTimer(Timer timer) {
        return Optional.of(writeDocument(timer, builder -> {
            builder.append(",\"count\":").append(timer.count());
            builder.append(",\"sum\":").append(timer.totalTime(getBaseTimeUnit()));
            builder.append(",\"mean\":").append(timer.mean(getBaseTimeUnit()));
            builder.append(",\"max\":").append(timer.max(getBaseTimeUnit()));
        }));
    }

    // VisibleForTesting
    Optional<String> writeSummary(DistributionSummary summary) {
        HistogramSnapshot histogramSnapshot = summary.takeSnapshot();
        return Optional.of(writeDocument(summary, builder -> {
            builder.append(",\"count\":").append(histogramSnapshot.count());
            builder.append(",\"sum\":").append(histogramSnapshot.total());
            builder.append(",\"mean\":").append(histogramSnapshot.mean());
            builder.append(",\"max\":").append(histogramSnapshot.max());
        }));
    }

    // VisibleForTesting
    Optional<String> writeMeter(Meter meter) {
        Iterable<Measurement> measurements = meter.measure();
        List<String> names = new ArrayList<>();
        // Snapshot values should be used throughout this method as there are chances for values to be changed in-between.
        List<Double> values = new ArrayList<>();
        for (Measurement measurement : measurements) {
            double value = measurement.getValue();
            if (!Double.isFinite(value)) {
                continue;
            }
            names.add(measurement.getStatistic().getTagValueRepresentation());
            values.add(value);
        }
        if (names.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(writeDocument(meter, builder -> {
            for (int i = 0; i < names.size(); i++) {
                builder.append(",\"").append(names.get(i)).append("\":\"").append(values.get(i)).append("\"");
            }
        }));
    }

    /**
     * Return formatted current timestamp.
     *
     * @return formatted current timestamp
     * @since 1.2.0
     */
    protected String generateTimestamp() {
        return TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(config().clock().wallTime()));
    }

    // VisibleForTesting
    String writeDocument(Meter meter, Consumer<StringBuilder> consumer) {
        StringBuilder sb = new StringBuilder(indexLine);
        String timestamp = generateTimestamp();
        String name = getConventionName(meter.getId());
        String type = meter.getId().getType().toString().toLowerCase();
        sb.append("{\"").append(config.timestampFieldName()).append("\":\"").append(timestamp).append('"')
                .append(",\"name\":\"").append(escapeJson(name)).append('"')
                .append(",\"type\":\"").append(type).append('"');

        List<Tag> tags = getConventionTags(meter.getId());
        for (Tag tag : tags) {
            sb.append(",\"").append(escapeJson(tag.getKey())).append("\":\"")
                    .append(escapeJson(tag.getValue())).append('"');
        }

        consumer.accept(sb);
        sb.append('}');

        return sb.toString();
    }

    @Override
    @NonNull
    protected TimeUnit getBaseTimeUnit() {
        return TimeUnit.MILLISECONDS;
    }

    public static class Builder {
        private final ElasticConfig config;

        private Clock clock = Clock.SYSTEM;
        private ThreadFactory threadFactory = DEFAULT_THREAD_FACTORY;
        private HttpSender httpClient;

        @SuppressWarnings("deprecation")
        Builder(ElasticConfig config) {
            this.config = config;
            this.httpClient = new HttpUrlConnectionSender(config.connectTimeout(), config.readTimeout());
        }

        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public Builder threadFactory(ThreadFactory threadFactory) {
            this.threadFactory = threadFactory;
            return this;
        }

        public Builder httpClient(HttpSender httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public ElasticMeterRegistry build() {
            return new ElasticMeterRegistry(config, clock, threadFactory, httpClient);
        }
    }
}
