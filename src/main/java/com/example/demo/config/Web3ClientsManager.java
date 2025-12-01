package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;
import org.web3j.protocol.websocket.WebSocketService;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Web3ClientsManager
 *
 * 管理多條區塊鏈的 Web3j client
 *
 * 功能：
 * - 初始化時，根據 ChainsProperties 建立每條鏈的 HTTP client
 * - 若 wsUrl 存在，建立並 connect WebSocketService 與對應 Web3j
 * - 提供 getHttp(chainName)、getWs(chainName) 與 getRequiredConfirmations(chainName)
 * - 管理每條鏈的 monitor 清單，方便後續訂閱事件
 * - shutdown 時關閉所有 WebSocketService 與 Web3j client
 */
@Slf4j
@Component
public class Web3ClientsManager {

    @Autowired
    private ChainsProperties chainsProperties; // 配置注入，包含所有鏈資訊

    private final Map<String, Web3j> httpClients = new ConcurrentHashMap<>(); // HTTP client map
    private final Map<String, Web3j> wsClients = new ConcurrentHashMap<>();   // WS client map
    private final Map<String, WebSocketService> wsServices = new ConcurrentHashMap<>(); // WS service map
    private final Map<String, Integer> requiredConfirmations = new ConcurrentHashMap<>(); // 每條鏈的確認數要求
    private final Map<String, List<ChainsProperties.Monitor>> monitors = new ConcurrentHashMap<>(); // 每條鏈的 monitor 清單

    /**
     * 初始化 clients 與 monitor 清單
     * 在 Spring Bean 完成注入後自動呼叫
     */
    @PostConstruct
    private void initClients() {
        if (chainsProperties.getChains() == null) {
            log.warn("No chains configured in ChainsProperties");
            return;
        }

        for (ChainsProperties.Chain chain : chainsProperties.getChains()) {
            String name = chain.getName();
            String httpUrl = chain.getHttpUrl();
            String wsUrl = chain.getWsUrl();
            int reqConf = chain.getRequiredConfirmations();

            if (name == null || httpUrl == null) {
                log.warn("Skipping chain with missing name or httpUrl");
                continue;
            }

            // 建立 HTTP client
            Web3j httpClient = Web3j.build(new HttpService(httpUrl));
            httpClients.put(name, httpClient);
            requiredConfirmations.put(name, reqConf);
            log.info("HTTP client initialized for chain={} url={}", name, httpUrl);

            // 建立 WS client (可選)
            if (wsUrl != null && !wsUrl.isBlank()) {
                try {
                    WebSocketService wsService = new WebSocketService(wsUrl, true);
                    wsService.connect(); // 連線 WS server
                    Web3j wsClient = Web3j.build(wsService);
                    wsClients.put(name, wsClient);
                    wsServices.put(name, wsService);
                    log.info("WS client connected for chain={} url={}", name, wsUrl);
                } catch (Exception ex) {
                    log.error("Failed to connect WS for chain={} url={} : {}", name, wsUrl, ex.getMessage(), ex);
                }
            } else {
                log.info("No wsUrl configured for chain={}, WS client not created", name);
            }

            // 初始化 monitor 清單
            if (chain.getMonitor() != null && !chain.getMonitor().isEmpty()) {
                monitors.put(name, chain.getMonitor());
                log.info("Monitor list loaded for chain={} with {} addresses", name, chain.getMonitor().size());
            } else {
                log.info("No monitor list configured for chain={}", name);
            }
        }
    }

    /**
     * 取得指定鏈的 HTTP client
     * @param chainName 鏈名稱
     * @return Web3j HTTP client，若不存在返回 null
     */
    public Web3j getHttp(String chainName) {
        return httpClients.get(chainName);
    }

    /**
     * 取得指定鏈的 WS client
     * @param chainName 鏈名稱
     * @return Web3j WS client，若不存在返回 null
     */
    public Web3j getWs(String chainName) {
        return wsClients.get(chainName);
    }

    /**
     * 取得指定鏈的 requiredConfirmations
     * @param chainName 鏈名稱
     * @return Optional<Integer>，若不存在則為空
     */
    public Optional<Integer> getRequiredConfirmations(String chainName) {
        return Optional.ofNullable(requiredConfirmations.get(chainName));
    }

    /**
     * 取得系統支援的鏈名稱
     * @return 不可變集合，包含所有 HTTP client 已初始化的鏈
     */
    public Set<String> supportedChains() {
        return Collections.unmodifiableSet(httpClients.keySet());
    }

    /**
     * 取得指定鏈的 monitor 清單
     * @param chainName 鏈名稱
     * @return List<ChainsProperties.Monitor>，若未配置則返回空列表
     */
    public List<ChainsProperties.Monitor> getMonitors(String chainName) {
        return monitors.getOrDefault(chainName, Collections.emptyList());
    }

    /**
     * 關閉所有 WS service 與 Web3j client
     * 在 Spring Bean 銷毀前呼叫
     */
    @PreDestroy
    public void shutdown() {
        // 關閉 WS service
        for (Map.Entry<String, WebSocketService> e : wsServices.entrySet()) {
            try {
                e.getValue().close();
                log.info("WS service closed for chain={}", e.getKey());
            } catch (Exception ex) {
                log.error("Failed to close WS service for chain={}: {}", e.getKey(), ex.getMessage(), ex);
            }
        }

        // 關閉 HTTP clients
        for (Map.Entry<String, Web3j> e : httpClients.entrySet()) {
            try {
                e.getValue().shutdown();
                log.info("HTTP client shutdown for chain={}", e.getKey());
            } catch (Exception ignore) {}
        }

        // 關閉 WS clients
        for (Map.Entry<String, Web3j> e : wsClients.entrySet()) {
            try {
                e.getValue().shutdown();
                log.info("WS client shutdown for chain={}", e.getKey());
            } catch (Exception ignore) {}
        }

        log.info("Web3ClientsManager shutdown completed.");
    }
}
