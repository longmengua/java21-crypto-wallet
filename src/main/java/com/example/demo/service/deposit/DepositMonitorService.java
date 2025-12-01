package com.example.demo.service.deposit;

import com.example.demo.config.ChainsProperties;
import com.example.demo.config.Web3ClientsManager;
import com.example.demo.constant.DepositStatus;
import com.example.demo.dao.Deposit;
import com.example.demo.repo.DepositRepository;
import io.reactivex.disposables.Disposable;
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
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * DepositMonitorService
 *
 * 用於監控多鏈充值交易（原生幣及 ERC20 Token）
 *
 * 功能：
 * - 啟動時自動訂閱各鏈的新區塊（WS 優先，HTTP fallback）
 * - 監控原生幣充值
 * - 監控 ERC20 Transfer Event
 * - 保存新入金到資料庫（避免重複）
 * - 定期檢查交易確認數，更新狀態：UNCONFIRMED → CONFIRMING → CONFIRMED
 * - stopAllSubscriptions() 停止所有 WS 訂閱及定時任務
 */
@Slf4j
@Service
public class DepositMonitorService {

    @Autowired
    private Web3ClientsManager clientsManager; // 多鏈 Web3 客戶端管理器

    @Autowired
    private DepositRepository depositRepo; // Deposit Repository

    private final ScheduledExecutorService confirmationScheduler = Executors.newScheduledThreadPool(5); // 確認數檢查
    private final Map<String, List<Disposable>> erc20Subscriptions = new ConcurrentHashMap<>(); // ERC20 WS 訂閱管理

    /**
     * 啟動監控
     */
    @PostConstruct
    public void start() {
        log.info("🔥 DepositMonitorService started");

        Set<String> chains = clientsManager.supportedChains();
        for (String chain : chains) {
            startMonitorForChain(chain);
        }
    }

    /**
     * 為指定鏈啟動監控
     */
    private void startMonitorForChain(String chainName) {
        Web3j ws = clientsManager.getWs(chainName);
        Web3j http = clientsManager.getHttp(chainName);
        int requiredConf = clientsManager.getRequiredConfirmations(chainName).orElse(12);

        log.info("開始監控鏈: {} | requiredConfirmations={}", chainName, requiredConf);

        // 取得該鏈監控清單
        List<ChainsProperties.Monitor> monitors = clientsManager.getMonitors(chainName);

        // WS 訂閱
        if (ws != null) {
            for (ChainsProperties.Monitor monitor : monitors) {
                String walletAddress = monitor.getWalletAddress();
                String tokenAddress = monitor.getTokenAddress();
                int tokenDecimals = monitor.getTokenDecimals();

                if (tokenAddress != null) { // ERC20 Token
                    Event transferEvent = new Event(
                            "Transfer",
                            Arrays.asList(
                                    TypeReference.create(Address.class, true),
                                    TypeReference.create(Address.class, true),
                                    TypeReference.create(Uint256.class)
                            )
                    );

                    EthFilter filter = new EthFilter(
                            DefaultBlockParameter.valueOf(BigInteger.valueOf(0)),
                            DefaultBlockParameter.valueOf(BigInteger.valueOf(Long.MAX_VALUE)),
                            tokenAddress
                    );
                    filter.addSingleTopic(EventEncoder.encode(transferEvent));

                    Disposable disposable = ws.ethLogFlowable(filter).subscribe(
                            logResult -> {
                                if (logResult instanceof EthLog.LogObject logObj) {
                                    handleErc20Log(chainName, walletAddress, tokenDecimals, transferEvent, logObj);
                                }
                            },
                            err -> log.error("[{}][WS][ERC20] 訂閱錯誤: {}", chainName, err.getMessage())
                    );

                    erc20Subscriptions.computeIfAbsent(chainName, k -> new ArrayList<>()).add(disposable);
                    log.info("[{}] ERC20 WS 訂閱啟動: token={}", chainName, tokenAddress);
                }
            }

            // 訂閱新區塊
            ws.blockFlowable(false).subscribe(
                    block -> {
                        try {
                            handleNewBlock(chainName, block.getBlock(), monitors);
                        } catch (Exception ex) {
                            log.error("[{}] 區塊處理錯誤: {}", chainName, ex.getMessage(), ex);
                        }
                    },
                    err -> log.error("[{}] WS 錯誤，HTTP fallback: {}", chainName, err.getMessage())
            );

        } else {
            log.info("[{}] 無 WS，使用 HTTP 輪詢 fallback", chainName);
        }

        startConfirmationChecker(chainName, http, requiredConf); // 啟動確認數檢查
    }

    /**
     * 處理新區塊中的原生幣交易
     */
    private void handleNewBlock(String chainName, EthBlock.Block block, List<ChainsProperties.Monitor> monitors) {
        long blockNumber = block.getNumber().longValue();
        log.debug("[{}] → 新區塊: {}", chainName, blockNumber);

        for (EthBlock.TransactionResult txObj : block.getTransactions()) {
            Object obj = txObj.get();
            if (obj instanceof Transaction tx) {
                for (ChainsProperties.Monitor monitor : monitors) {
                    checkNativeTransfer(chainName, tx, monitor.getWalletAddress(), blockNumber);
                }
            }
        }
    }

    /**
     * 檢查原生幣入金
     */
    private void checkNativeTransfer(String chainName, Transaction tx, String monitoredAddress, long blockNumber) {
        if (tx.getTo() == null || !tx.getTo().equalsIgnoreCase(monitoredAddress)) return;
        BigInteger amountWei = tx.getValue();
        if (amountWei.equals(BigInteger.ZERO)) return;

        BigDecimal amount = new BigDecimal(amountWei).divide(BigDecimal.TEN.pow(18));
        log.info("[{}] 💰 偵測到原生幣充值: tx={}, amount={}", chainName, tx.getHash(), amount);
        saveNewDeposit(tx.getHash(), chainName, "NATIVE", amount, blockNumber);
    }

    /**
     * 處理 ERC20 入金
     */
    private void handleErc20Log(String chainName, String monitoredAddress, int tokenDecimals, Event transferEvent, EthLog.LogObject logEntry) {
        try {
            // indexed to 參數解碼
            List<Type> indexedValues = Collections.singletonList(FunctionReturnDecoder.decodeIndexedValue(
                    logEntry.getTopics().get(2), TypeReference.create(Address.class)
            ));
            String to = ((Address) indexedValues.get(0)).getValue();
            if (!to.equalsIgnoreCase(monitoredAddress)) return;

            // 非 indexed value (amount)
            List<Type> decoded = FunctionReturnDecoder.decode(
                    logEntry.getData(),
                    transferEvent.getNonIndexedParameters()
            );
            BigInteger rawAmount = (BigInteger) decoded.get(0).getValue();
            BigDecimal amount = new BigDecimal(rawAmount).divide(BigDecimal.TEN.pow(tokenDecimals));

            log.info("[{}] 💰 偵測到 ERC20 入金: tx={}, amount={}", chainName, logEntry.getTransactionHash(), amount);
            saveNewDeposit(logEntry.getTransactionHash(), chainName, "ERC20", amount, 0);
        } catch (Exception ex) {
            log.error("[{}] ERC20 Log 處理錯誤: {}", chainName, ex.getMessage(), ex);
        }
    }

    /**
     * 保存新入金
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
        dep.setStatus(DepositStatus.UNCONFIRMED);

        depositRepo.save(dep);
        log.info("[{}] 📥 已記錄入金: {}", chainName, txHash);
    }

    /**
     * 啟動定期確認數檢查任務
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
     * 檢查入金確認數
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

    /**
     * 停止所有 WS 訂閱與定時任務
     */
    public void stopAllSubscriptions() {
        erc20Subscriptions.values().forEach(list -> list.forEach(Disposable::dispose));
        erc20Subscriptions.clear();
        confirmationScheduler.shutdown();
        log.info("DepositMonitorService subscriptions stopped.");
    }
}
