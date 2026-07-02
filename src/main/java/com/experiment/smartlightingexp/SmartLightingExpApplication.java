package com.experiment.smartlightingexp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class SmartLightingExpApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartLightingExpApplication.class, args);
    }

}
