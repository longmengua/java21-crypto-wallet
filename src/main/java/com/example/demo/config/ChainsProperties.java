package com.example.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "app")
public class ChainsProperties {
    private List<Chain> chains;

    @Setter
    @Getter
    public static class Chain {
        // getters / setters
        private String name;
        private String wsUrl;
        private String httpUrl;
        private int requiredConfirmations;
    }
}

