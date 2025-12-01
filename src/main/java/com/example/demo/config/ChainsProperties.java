package com.example.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Setter
@Getter
@Configuration
@ConfigurationProperties(prefix = "chains")
public class ChainsProperties {

    private List<Chain> chains; // 所有鏈的清單

    @Setter
    @Getter
    public static class Chain {
        private String name;                 // 鏈名稱
        private String wsUrl;                // WebSocket URL
        private String httpUrl;              // HTTP URL
        private int requiredConfirmations;   // 交易確認數
        private List<Monitor> monitor;       // 要監控的錢包與 token 清單
    }

    @Setter
    @Getter
    public static class Monitor {
        private String walletAddress;       // 要監控的錢包地址
        private String tokenAddress;        // null 表示監控原生幣，否則填 ERC20 token address
        private int tokenDecimals;          // token 小數位數
    }
}
