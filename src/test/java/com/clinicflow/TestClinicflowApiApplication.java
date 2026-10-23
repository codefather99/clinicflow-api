package com.clinicflow;

import org.springframework.boot.SpringApplication;

public class TestClinicflowApiApplication {

    public static void main(String[] args) {
        SpringApplication.from(ClinicflowApiApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
