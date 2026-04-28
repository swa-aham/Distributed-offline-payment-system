package com.offlineupi.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EdgeNodeApplication {
    public static void main(String[] args) {
        SpringApplication.run(EdgeNodeApplication.class, args);
    }
}
