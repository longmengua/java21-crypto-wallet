package com.example.demo.config;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.websocket.WebSocketService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web3ClientsManager
 * - init 時會根據 ChainsProperties 建立每條鏈的 http client
 * - 若 wsUrl 存在，會嘗試建立並 connect WebSocketService 以及對應的 Web3j
 * - 提供 getHttp(chainName) / getWs(chainName) 與 getRequiredConfirmations(chainName)
 * - shutdown 時會關閉所有 WebSocketService（若建立過）
 */
@Component
public class Web3ClientsManager {

    private final ChainsProperties chainsProperties;

    // maps keyed by chain name (e.g. "ETH", "BSC")
    private final Map<String, Web3j> httpClients = new ConcurrentHashMap<>();
    private final Map<String, Web3j> wsClients = new ConcurrentHashMap<>();
    private final Map<String, WebSocketService> wsServices = new ConcurrentHashMap<>();
    private final Map<String, Integer> requiredConfirmations = new ConcurrentHashMap<>();

    public Web3ClientsManager(ChainsProperties chainsProperties) {
        this.chainsProperties = chainsProperties;
        initClients();
    }

    private void initClients() {
        if (chainsProperties.getChains() == null) return;

        for (ChainsProperties.Chain chain : chainsProperties.getChains()) {
            String name = chain.getName();
            String httpUrl = chain.getHttpUrl();
            String wsUrl = chain.getWsUrl();
            int reqConf = chain.getRequiredConfirmations();

            if (name == null || httpUrl == null) {
                // skip invalid config
                continue;
            }

            // HTTP client (always)
            Web3j httpClient = Web3j.build(new HttpService(httpUrl));
            httpClients.put(name, httpClient);
            requiredConfirmations.put(name, reqConf);

            // WS client (optional)
            if (wsUrl != null && !wsUrl.isBlank()) {
                try {
                    WebSocketService wsService = new WebSocketService(wsUrl, true);
                    wsService.connect(); // may throw ConnectException
                    Web3j wsClient = Web3j.build(wsService);
                    wsClients.put(name, wsClient);
                    wsServices.put(name, wsService);
                    System.out.printf("WS connected for chain=%s url=%s%n", name, wsUrl);
                } catch (Exception ex) {
                    // 連線失敗時記錄，但不阻止應用啟動（視情況決定是拋例外還是容忍）
                    ex.printStackTrace();
                    System.err.printf("Failed to connect WS for chain=%s url=%s: %s%n", name, wsUrl, ex.getMessage());
                }
            } else {
                System.out.printf("No wsUrl for chain=%s, WS client not created%n", name);
            }
        }
    }

    public Web3j getHttp(String chainName) {
        return httpClients.get(chainName);
    }

    /**
     * 可能回傳 null（若未設定 ws 或建立失敗）
     */
    public Web3j getWs(String chainName) {
        return wsClients.get(chainName);
    }

    public Optional<Integer> getRequiredConfirmations(String chainName) {
        return Optional.ofNullable(requiredConfirmations.get(chainName));
    }

    public Set<String> supportedChains() {
        return Collections.unmodifiableSet(httpClients.keySet());
    }

    @PreDestroy
    public void shutdown() {
        // 關閉 WS services
        for (Map.Entry<String, WebSocketService> e : wsServices.entrySet()) {
            try {
                e.getValue().close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        // close web3j clients
        for (Web3j w : httpClients.values()) {
            try { w.shutdown(); } catch (Exception ignore) {}
        }
        for (Web3j w: wsClients.values()) {
            try { w.shutdown(); } catch (Exception ignore) {}
        }
        System.out.println("Web3ClientsManager shutdown completed.");
    }
}

