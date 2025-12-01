package com.example.demo.config;

import lombok.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@ToString
@Builder
@Configuration
public class MonitorConfig {
    @Value("${monitor.monitoredAddress}")
    private String monitoredAddress;
    @Value("${monitor.tokenContract:#{null}}")
    private String tokenContractAddress;
    @Value("${monitor.tokenDecimals:18}")
    private int tokenDecimals;
}
