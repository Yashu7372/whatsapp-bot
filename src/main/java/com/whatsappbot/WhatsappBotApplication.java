package com.whatsappbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WhatsappBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(WhatsappBotApplication.class, args);
    }
}
