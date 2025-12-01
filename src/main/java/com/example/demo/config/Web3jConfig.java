package com.example.demo.config;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.websocket.WebSocketService;

import java.net.ConnectException;

/**
 * 建立兩個 Web3j bean：
 *  - web3Http : 永遠建立（以 HTTP RPC 為主）
 *  - web3Ws   : 只有在 app.chains[0].wsUrl 有設定時才建立（可選）
 *
 * 使用 @ConditionalOnProperty 讓 ws bean 在沒設定時不會創建，避免 Spring 啟動錯誤。
 */
@Configuration
public class Web3jConfig {

    @Value("${app.chains[0].httpUrl}")
    private String httpUrl;

    // 如果沒有設定 wsUrl，則不建立 web3Ws bean
    @Value("${app.chains[0].wsUrl:}")
    private String wsUrl;

    private WebSocketService wsServiceRef;

    /**
     * Http Web3j bean（供查詢/輪詢使用）
     */
    @Bean(name = "web3Http")
    public Web3j web3Http() {
        HttpService httpService = new HttpService(httpUrl);
        return Web3j.build(httpService);
    }

    /**
     * WebSocket Web3j bean（僅當 app.chains[0].wsUrl 有設定時建立）
     * 使用 ConditionalOnProperty 讓 bean 只在 property 存在且非空時建立。
     */
    @Bean(name = "web3Ws")
    @ConditionalOnProperty(prefix = "app.chains[0]", name = "wsUrl")
    public Web3j web3Ws() throws Exception {
        // 如果 wsUrl 為空字串，也透過 ConditionalOnProperty 不會到這裡
        WebSocketService ws = new WebSocketService(wsUrl, true);
        try {
            ws.connect(); // 立刻 connect，若失敗會丟例外
        } catch (ConnectException ce) {
            // 連線失敗時可選擇：
            //  - 拋出例外讓應用啟動失敗（提示運維）
            //  - 或記錄並回傳 null（此處我們拋例外較安全）
            throw new RuntimeException("Failed to connect to WebSocket provider: " + wsUrl, ce);
        }
        this.wsServiceRef = ws;
        return Web3j.build(ws);
    }

    /**
     * 應用關閉時，關閉 WebSocketService（若建立過）
     */
    @PreDestroy
    public void shutdown() {
        try {
            if (wsServiceRef != null) {
                wsServiceRef.close();
            }
        } catch (Exception e) {
            // 記錄但不阻塞關閉流程
            e.printStackTrace();
        }
    }
}
