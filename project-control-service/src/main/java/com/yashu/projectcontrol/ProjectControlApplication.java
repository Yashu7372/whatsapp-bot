package com.yashu.projectcontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ProjectControlApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProjectControlApplication.class, args);
    }
}
