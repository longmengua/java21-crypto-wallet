package com.example.demo.service.deposit;

import com.example.demo.config.Web3ClientsManager;
import com.example.demo.config.MonitorConfig;
import com.example.demo.constant.DepositStatus;
import com.example.demo.dao.Deposit;
import com.example.demo.repo.DepositRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Transaction;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class DepositMonitorService {

    @Autowired
    private Web3ClientsManager clientsManager;  // 管理多鏈 Web3j 客戶端（WS + HTTP）
    @Autowired
    private DepositRepository depositRepo;      // 入金交易庫
    @Autowired
    private MonitorConfig monitorConfig;        // 監控地址與 Token 設定

    private ScheduledExecutorService confirmationScheduler = Executors.newScheduledThreadPool(5);

    /**
     * Spring Boot 啟動後自動執行
     * → 啟動所有鏈的充值監控任務
     */
    @PostConstruct
    public void start() {
        log.info("🔥 DepositMonitorService started");
        log.info("Monitoring address = {}", monitorConfig.getMonitoredAddress());
        log.info("Token contract     = {}", monitorConfig.getTokenContractAddress());

        Set<String> chains = clientsManager.supportedChains();
        for (String chain : chains) {
            startMonitorForChain(chain);
        }
    }

    /**
     * 為指定鏈啟動監控
     * → 優先使用 WebSocket 訂閱新區塊
     * → 無 WS 則 fallback 使用 HTTP 輪詢
     */
    private void startMonitorForChain(String chainName) {
        Web3j ws = clientsManager.getWs(chainName);     // WebSocket 客戶端
        Web3j http = clientsManager.getHttp(chainName); // HTTP 客戶端
        int requiredConf = clientsManager.getRequiredConfirmations(chainName).orElse(12);

        log.info("開始監控鏈: {} | requiredConfirmations={}", chainName, requiredConf);

        // WS 存在 → 訂閱新區塊事件
        if (ws != null) {
            ws.blockFlowable(false).subscribe(block -> {
                try {
                    handleNewBlock(chainName, block.getBlock());
                } catch (Exception ex) {
                    log.error("[{}] 區塊處理錯誤: {}", chainName, ex.getMessage(), ex);
                }
            }, err -> log.error("[{}] WS 錯誤，fallback HTTP polling: {}", chainName, err.getMessage()));
        } else {
            log.info("[{}] 無 WS，使用 HTTP 輪詢 fallback", chainName);
        }

        // 啟動背景確認數檢查
        startConfirmationChecker(chainName, http, requiredConf);
    }

    /**
     * 處理新區塊
     * → 掃描所有交易
     * → ERC20 Token 掃描 Transfer Event
     * → 原生幣直接檢查交易收款地址
     */
    private void handleNewBlock(String chainName, EthBlock.Block block) {
        long blockNumber = block.getNumber().longValue();
        log.debug("[{}] → 新區塊: {}", chainName, blockNumber);

        // ERC20 Token 使用 Event Filter，不需要逐筆交易
        if (monitorConfig.getTokenContractAddress() != null) {
            checkErc20Transfer(chainName, blockNumber);
        }

        // 原生幣充值檢查
        for (EthBlock.TransactionResult txObj : block.getTransactions()) {
            Object obj = txObj.get();
            if (obj instanceof Transaction tx) {
                checkNativeTransfer(chainName, tx, blockNumber);
            } else {
                log.info("[{}] 跳過非 Transaction 對象: {}", chainName, obj);
            }
        }
    }

    /**
     * 監控原生幣充值（ETH/BNB/MATIC）
     * → 檢查收款地址是否匹配
     * → 非零金額才記錄
     */
    private void checkNativeTransfer(String chainName, Transaction tx, long blockNumber) {
        if (tx.getTo() == null) return;
        if (!tx.getTo().equalsIgnoreCase(monitorConfig.getMonitoredAddress())) return;

        BigInteger amountWei = tx.getValue();
        if (amountWei.equals(BigInteger.ZERO)) return;

        BigDecimal amount = new BigDecimal(amountWei).divide(BigDecimal.TEN.pow(18));

        log.info("[{}] 💰 偵測到原生幣充值: tx={}, amount={}", chainName, tx.getHash(), amount);
        saveNewDeposit(tx.getHash(), chainName, "NATIVE", amount, blockNumber);
    }

    /**
     * 監控 ERC20 Transfer Event
     * → 改成 WS 訂閱，避免 HTTP 429
     * → HTTP 仍做備援，加入重試與退避
     */
    private void checkErc20Transfer(String chainName, long blockNumber) {
        Web3j ws = clientsManager.getWs(chainName);
        Web3j http = clientsManager.getHttp(chainName);

        Event transferEvent = new Event(
                "Transfer",
                Arrays.asList(
                        TypeReference.create(Address.class, true), // from
                        TypeReference.create(Address.class, true), // to
                        TypeReference.create(Uint256.class)        // value
                )
        );

        // WS 存在 → 使用 WS Flowable 訂閱事件
        if (ws != null) {
            EthFilter filter = new EthFilter(
                    DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
                    DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
                    monitorConfig.getTokenContractAddress()
            );
            filter.addSingleTopic(EventEncoder.encode(transferEvent));

            ws.ethLogFlowable(filter).subscribe(logResult -> {
                if (!(logResult instanceof EthLog.LogObject logEntry)) return;

                try {
                    List<Type> indexedValues = Collections.singletonList(
                            FunctionReturnDecoder.decodeIndexedValue(
                                    logEntry.getTopics().get(2),
                                    TypeReference.create(Address.class)
                            )
                    );
                    String to = ((Address) indexedValues.get(0)).getValue();
                    if (!to.equalsIgnoreCase(monitorConfig.getMonitoredAddress())) return;

                    List<Type> decoded = FunctionReturnDecoder.decode(
                            logEntry.getData(),
                            transferEvent.getNonIndexedParameters()
                    );

                    BigInteger rawAmount = (BigInteger) decoded.get(0).getValue();
                    BigDecimal amount = new BigDecimal(rawAmount)
                            .divide(BigDecimal.TEN.pow(monitorConfig.getTokenDecimals()));

                    log.info("[{}][WS] 💰 偵測到 ERC20 入金: tx={}, amount={}", chainName, logEntry.getTransactionHash(), amount);
                    saveNewDeposit(logEntry.getTransactionHash(), chainName, "ERC20", amount, blockNumber);
                } catch (Exception e) {
                    log.error("[{}][WS] ERC20 解碼錯誤: {}", chainName, e.getMessage(), e);
                }
            }, err -> log.error("[{}][WS] ERC20 訂閱錯誤: {}", chainName, err.getMessage()));
        }
        // WS 不存在 → HTTP 備援，加入重試 + 退避
        else if (http != null) {
            int retries = 0;
            boolean success = false;
            while (!success && retries < 5) {
                try {
                    EthFilter filter = new EthFilter(
                            DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
                            DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
                            monitorConfig.getTokenContractAddress()
                    );
                    filter.addSingleTopic(EventEncoder.encode(transferEvent));

                    List<EthLog.LogResult> logs = http.ethGetLogs(filter).send().getLogs();
                    if (logs == null || logs.isEmpty()) return;

                    for (EthLog.LogResult logObj : logs) {
                        if (!(logObj instanceof EthLog.LogObject logEntry)) continue;

                        List<Type> indexedValues = Collections.singletonList(
                                FunctionReturnDecoder.decodeIndexedValue(
                                        logEntry.getTopics().get(2),
                                        TypeReference.create(Address.class)
                                )
                        );

                        String to = ((Address) indexedValues.get(0)).getValue();
                        if (!to.equalsIgnoreCase(monitorConfig.getMonitoredAddress())) continue;

                        List<Type> decoded = FunctionReturnDecoder.decode(
                                logEntry.getData(),
                                transferEvent.getNonIndexedParameters()
                        );

                        BigInteger rawAmount = (BigInteger) decoded.get(0).getValue();
                        BigDecimal amount = new BigDecimal(rawAmount)
                                .divide(BigDecimal.TEN.pow(monitorConfig.getTokenDecimals()));

                        log.info("[{}][HTTP] 💰 偵測到 ERC20 入金: tx={}, amount={}", chainName, logEntry.getTransactionHash(), amount);
                        saveNewDeposit(logEntry.getTransactionHash(), chainName, "ERC20", amount, blockNumber);
                    }
                    success = true;
                } catch (Exception ex) {
                    if (ex.getMessage().contains("429")) {
                        retries++;
                        try { Thread.sleep(1000L * retries); } catch (InterruptedException ignored) {}
                        log.warn("[{}][HTTP] 429 Too Many Requests, retry #{}", chainName, retries);
                    } else {
                        log.error("[{}][HTTP] ERC20 掃描錯誤: {}", chainName, ex.getMessage(), ex);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 保存入金交易到資料庫
     * → 避免重複記錄
     * → 記錄鏈名、資產類型、金額、區塊高度、狀態
     */
    private void saveNewDeposit(String txHash, String chainName, String asset, BigDecimal amount, long blockNumber) {
        if (depositRepo.findByTxHash(txHash).isPresent()) {
            log.debug("[{}] 跳過 (已存在): {}", chainName, txHash);
            return;
        }

        Deposit dep = new Deposit();
        dep.setTxHash(txHash);
        dep.setAsset(asset);
        dep.setChain(chainName);
        dep.setAmount(amount);
        dep.setBlockNumber(blockNumber);
        dep.setStatus(DepositStatus.UNCONFIRMED); // 初始狀態

        depositRepo.save(dep);
        log.info("[{}] 📥 已記錄入金: {}", chainName, txHash);
    }

    /**
     * 背景任務：定期檢查確認數
     * → 使用 ScheduledExecutorService 替代無限 Thread + sleep
     */
    private void startConfirmationChecker(String chainName, Web3j http, int requiredConfirmations) {
        confirmationScheduler.scheduleAtFixedRate(() -> {
            try {
                checkConfirmations(chainName, http, requiredConfirmations);
            } catch (Exception ex) {
                log.error("[{}] 確認數檢查錯誤: {}", chainName, ex.getMessage(), ex);
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    /**
     * 檢查指定鏈的待確認交易
     * → 查詢狀態為 UNCONFIRMED 或 CONFIRMING 的交易
     * → 計算確認數，更新狀態
     */
    private void checkConfirmations(String chainName, Web3j http, int requiredConfirmations) throws IOException {
        List<Deposit> pendingList = depositRepo.findByStatusIn(
                List.of(DepositStatus.UNCONFIRMED, DepositStatus.CONFIRMING)
        );
        if (pendingList.isEmpty()) return;

        long currentBlock = http.ethBlockNumber().send().getBlockNumber().longValue();

        for (Deposit dep : pendingList) {
            if (!dep.getChain().equalsIgnoreCase(chainName)) continue;

            long confirmCount = currentBlock - dep.getBlockNumber();
            if (confirmCount < 0) continue;

            if (confirmCount >= requiredConfirmations) {
                dep.setStatus(DepositStatus.CONFIRMED);
                depositRepo.save(dep);
                log.info("[{}] 🎉 充值完成: tx={} (+{} confirms)", chainName, dep.getTxHash(), confirmCount);
            } else {
                dep.setStatus(DepositStatus.CONFIRMING);
                depositRepo.save(dep);
                log.info("[{}] ⏳ 充值確認中: tx={} ({}/{})", chainName, dep.getTxHash(), confirmCount, requiredConfirmations);
            }
        }
    }
}
