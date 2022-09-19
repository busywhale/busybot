package com.busywhale.busybot;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

@SpringBootApplication
@ComponentScan(basePackages = {"com.busywhale.busybot"})
public class BusyBotMain implements ApplicationRunner {
    private static final Logger logger = LogManager.getLogger(BusyBotMain.class);

    @Autowired
    private ApplicationContext context;

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(BusyBotMain.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.run(args);
    }

    @PostConstruct
    @SuppressWarnings("unused")
    public void init() {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logger.error("Uncaught exception from thread {}", t.getName(), e);
            System.exit(SpringApplication.exit(context, () -> 1));
        });
    }

    @PreDestroy
    @SuppressWarnings("unused")
    public void destroy() {
        logger.info("BusyBot is stopped.");
    }

    @Override
    public void run(ApplicationArguments args) {
        logger.info("BusyBot has started...");
    }
}
