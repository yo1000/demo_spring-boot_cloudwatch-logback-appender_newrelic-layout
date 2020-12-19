package com.yo1000.demo.spring_cloud_watch_logback_appender_newrelic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CountUpPrinter {
    private final Logger LOGGER = LoggerFactory.getLogger(CountUpPrinter.class);

    private int count = 0;

    public void print() {
        LOGGER.info("{}", count++);
    }
}
