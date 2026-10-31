package com.nox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NoxApplication {

    public static void main(String[] args) {
        SpringApplication.run(NoxApplication.class, args);
    }
}
