package com.yo1000.demo.spring_cloud_watch_logback_appender_newrelic;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.AWSLogsClient;
import com.amazonaws.services.logs.AWSLogsClientBuilder;
import com.amazonaws.services.logs.model.DescribeLogStreamsRequest;
import com.amazonaws.services.logs.model.GetLogEventsRequest;
import com.amazonaws.services.logs.model.OutputLogEvent;
import com.j256.cloudwatchlogbackappender.CloudWatchAppender;
import org.assertj.core.api.AssertProvider;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.json.JsonContentAssert;
import org.springframework.util.ReflectionUtils;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * Parameter sources are exists in envvars
 * Refs: maven-surefire-plugin section in pom.xml
 */
@SpringBootTest
public class CloudWatchLoggingTests {
    static final String REGION = "ap-northeast-1";
    static final String ACCESS_KEY_ID = "localstackAccessKeyId";
    static final String SECRET_ACCESS_KEY = "localstackSecretAccessKey";
    static final String LOG_GROUP = "localstackLogGroup";
    static final String LOG_STREAM = "localstackLogStream";

    static LocalStackContainer localstack;

    @Autowired
    CountUpPrinter printer;

    @BeforeAll
    static void setupLocalStack() {
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:0.12.3"))
                .withServices(LocalStackContainer.Service.CLOUDWATCHLOGS);
        localstack.start();
    }

    @AfterAll
    static void teardownLocalStack() {
        localstack.stop();
    }

    @Test
    void test__Given_CloudWatchLogger__When_something_logging__Then_write_log_to_CloudWatchLogs() throws Exception {
        long currentTime = System.currentTimeMillis();

        AWSLogsClientBuilder clientBuilder = AWSLogsClient.builder();
        clientBuilder.setEndpointConfiguration(
                localstack.getEndpointConfiguration(LocalStackContainer.Service.CLOUDWATCHLOGS)
        );
        clientBuilder.setCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(
                ACCESS_KEY_ID,
                SECRET_ACCESS_KEY
        )));

        AWSLogs client = clientBuilder.build();

        // LogGroup and LogStream are auto creation by CloudWatchAppender
        // client.createLogGroup(new CreateLogGroupRequest(LOG_GROUP));
        // client.createLogStream(new CreateLogStreamRequest(LOG_GROUP, LOG_STREAM));

        Logger logger = LoggerFactory.getLogger(this.getClass());

        if (!(logger instanceof ch.qos.logback.classic.Logger)) {
            Assertions.fail("");
            return;
        }

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        for (ch.qos.logback.classic.Logger logbackLogger : context.getLoggerList()) {
            for (Iterator<Appender<ILoggingEvent>> index = logbackLogger.iteratorForAppenders(); index.hasNext();) {
                Appender<ILoggingEvent> appender = index.next();

                if (appender instanceof CloudWatchAppender) {
                    Optional<Method> setAwsLogsClient = Arrays.stream(ReflectionUtils
                            .getDeclaredMethods(CloudWatchAppender.class))
                            .filter(m -> m.getName().equals("setAwsLogsClient")).findFirst();

                    if (setAwsLogsClient.isPresent()) {
                        ReflectionUtils.makeAccessible(setAwsLogsClient.get());
                        ReflectionUtils.invokeMethod(setAwsLogsClient.get(), appender, client);
                    } else {
                        Assertions.fail("setAwsLogsClient is not found");
                    }
                }
            }
        }

        logger.debug("Debug message");
        logger.info("Info message");
        logger.warn("Warn message");
        logger.error("Error message");

        printer.print();
        printer.print();
        printer.print();

        // Wait for LogGroup and LogStream auto creation latency
        Thread.sleep(10000L);

        client.describeLogStreams(new DescribeLogStreamsRequest(LOG_GROUP))
                .getLogStreams()
                .forEach(logStream -> {
                    System.out.println(logStream.getLogStreamName());
                });

        List<OutputLogEvent> logEvents = client.getLogEvents(new GetLogEventsRequest(LOG_GROUP, LOG_STREAM) {{
            setStartTime(currentTime);
        }}).getEvents();

        Assertions.assertThat(forJson(logEvents.get(0).getMessage()))
                .extractingJsonPathStringValue("$.message")
                .isEqualTo("Info message");

        Assertions.assertThat(forJson(logEvents.get(1).getMessage()))
                .extractingJsonPathStringValue("$.message")
                .isEqualTo("Warn message");

        Assertions.assertThat(forJson(logEvents.get(2).getMessage()))
                .extractingJsonPathStringValue("$.message")
                .isEqualTo("Error message");

        Assertions.assertThat(forJson(logEvents.get(3).getMessage()))
                .extractingJsonPathStringValue("$.message")
                .isEqualTo("0");

        Assertions.assertThat(forJson(logEvents.get(4).getMessage()))
                .extractingJsonPathStringValue("$.message")
                .isEqualTo("1");

        Assertions.assertThat(forJson(logEvents.get(5).getMessage()))
                .extractingJsonPathStringValue("$.message")
                .isEqualTo("2");
    }

    AssertProvider<JsonContentAssert> forJson(String json) {
        return new AssertProvider<JsonContentAssert>() {
            @Override
            public JsonContentAssert assertThat() {
                return new JsonContentAssert(this.getClass(), json);
            }
        };
    }
}
