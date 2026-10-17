package com.example.recipient;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class RecipientResolverApplication {
    public static void main(String[] args) { SpringApplication.run(RecipientResolverApplication.class, args); }
}
