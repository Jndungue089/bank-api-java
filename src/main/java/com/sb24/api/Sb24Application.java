package com.sb24.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Sb24Application {
    public static void main(String[] args) {
        SpringApplication.run(Sb24Application.class, args);
    }
}
