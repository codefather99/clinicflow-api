package com.clinicflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.clinicflow")
public class ClinicflowApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClinicflowApiApplication.class, args);
    }

}
