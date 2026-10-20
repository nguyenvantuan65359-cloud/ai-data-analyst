package com.example.analyst;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.example")
public class AnalystApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnalystApplication.class, args);
    }
}
