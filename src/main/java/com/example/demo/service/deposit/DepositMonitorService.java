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
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class DepositMonitorService {
    @Autowired
    private Web3ClientsManager clientsManager;
    @Autowired
    private DepositRepository depositRepo;
    @Autowired
    private MonitorConfig monitorConfig;

    /**
     * 啟動所有鏈的監控任務
     */
    @PostConstruct
    public void start() {
        log.info("🔥 DepositMonitorService started");
        log.info("Monitoring address = {}", monitorConfig.getMonitoredAddress());
        log.info("Token contract     = {}", monitorConfig.getTokenContractAddress());

        // 取得支援的鏈名
        Set<String> chains = clientsManager.supportedChains();
        for (String chain : chains) {
            startMonitorForChain(chain);
        }
    }

    /**
     * 為指定鏈啟動監控（WS優先，HTTP fallback）
     */
    private void startMonitorForChain(String chainName) {
        Web3j ws = clientsManager.getWs(chainName);
        Web3j http = clientsManager.getHttp(chainName);
        int requiredConf = clientsManager.getRequiredConfirmations(chainName).orElse(12);

        log.info("開始監控鏈: {} | requiredConfirmations={}", chainName, requiredConf);

        // WS 存在 → 訂閱新區塊
        if (ws != null) {
            ws.blockFlowable(false).subscribe(block -> {
                try {
                    handleNewBlock(chainName, block.getBlock());
                } catch (Exception ex) {
                    log.error("[{}] 區塊處理錯誤: {}", chainName, ex.getMessage(), ex);
                }
            }, err -> {
                log.error("[{}] WS 錯誤，fallback HTTP polling: {}", chainName, err.getMessage());
                // 可在此啟動 HTTP 輪詢 fallback（未實作）
            });
        } else {
            log.info("[{}] 無 WS，使用 HTTP 輪詢 fallback", chainName);
            // 可在此啟動 HTTP 輪詢 fallback（未實作）
        }

        // 背景確認數檢查
        startConfirmationChecker(chainName, http, requiredConf);
    }

    /**
     * 處理新區塊（ERC20 & 原生幣）
     */
    private void handleNewBlock(String chainName, EthBlock.Block block) {
        long blockNumber = block.getNumber().longValue();
        log.debug("[{}] → 新區塊: {}", chainName, blockNumber);

        List<EthBlock.TransactionResult> txs = block.getTransactions();

        for (EthBlock.TransactionResult txObj : txs) {
            Transaction tx = (Transaction) txObj.get();

            // ERC20：只掃描 Transfer event，不看每一筆 tx
            if (monitorConfig.getTokenContractAddress() != null) {
                checkErc20Transfer(chainName, blockNumber);
                break;
            }

            // 原生幣充值
            checkNativeTransfer(chainName, tx, blockNumber);
        }
    }

    /**
     * 監控原生幣充值（ETH/BNB/MATIC）
     */
    private void checkNativeTransfer(String chainName, Transaction tx, long blockNumber) {
        if (tx.getTo() == null) return;
        if (!tx.getTo().equalsIgnoreCase(monitorConfig.getMonitoredAddress())) return;

        BigInteger amountWei = tx.getValue();
        if (amountWei.equals(BigInteger.ZERO)) return;

        BigDecimal amount = new BigDecimal(amountWei);

        log.info("[{}] 💰 偵測到原生幣充值: tx={}, amount(wei)={}", chainName, tx.getHash(), amount);

        saveNewDeposit(tx.getHash(), chainName, "NATIVE", amount, blockNumber);
    }

    /**
     * 監控 ERC20 Transfer Event
     */
    private void checkErc20Transfer(String chainName, long blockNumber) {
        try {
            Web3j http = clientsManager.getHttp(chainName);

            Event transferEvent = new Event(
                    "Transfer",
                    Arrays.asList(
                            TypeReference.create(Address.class, true),
                            TypeReference.create(Address.class, true),
                            TypeReference.create(Uint256.class)
                    )
            );

            EthFilter filter = new EthFilter(
                    DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
                    DefaultBlockParameter.valueOf(BigInteger.valueOf(blockNumber)),
                    monitorConfig.getTokenContractAddress()
            );

            filter.addSingleTopic(EventEncoder.encode(transferEvent));

            http.ethGetLogs(filter).send().getLogs().forEach(logObj -> {
                EthLog.LogObject logEntry = (EthLog.LogObject) logObj;
                List<String> topics = logEntry.getTopics();

                String to = "0x" + topics.get(2).substring(26).toLowerCase();
                if (!to.equals(monitorConfig.getMonitoredAddress())) return;

                List<Type> decoded = FunctionReturnDecoder.decode(
                        logEntry.getData(),
                        transferEvent.getNonIndexedParameters()
                );

                BigInteger rawAmount = (BigInteger) decoded.get(0).getValue();
                BigDecimal amount = new BigDecimal(rawAmount)
                        .divide(BigDecimal.TEN.pow(monitorConfig.getTokenDecimals()));

                log.info("[{}] 💰 偵測到 ERC20 入金: tx={}, amount={}", chainName, logEntry.getTransactionHash(), amount);

                saveNewDeposit(logEntry.getTransactionHash(), chainName, "ERC20", amount, blockNumber);
            });

        } catch (Exception ex) {
            log.error("[{}] ERC20 掃描錯誤: {}", chainName, ex.getMessage(), ex);
        }
    }

    /**
     * 保存交易入庫（避免重複記錄）
     */
    private void saveNewDeposit(String txHash, String chainName, String asset, BigDecimal amount, long blockNumber) {
        if (depositRepo.findByTxHash(txHash).isPresent()) {
            log.debug("[{}] 跳過 (已存在): {}", chainName, txHash);
            return;
        }

        Deposit dep = new Deposit();
        dep.setTxHash(txHash);
        dep.setAsset(asset);
        dep.setChain(chainName); // 多鏈字段
        dep.setAmount(amount);
        dep.setBlockNumber(blockNumber);
        dep.setStatus(DepositStatus.UNCONFIRMED);

        depositRepo.save(dep);
        log.info("[{}] 📥 已記錄入金: {}", chainName, txHash);
    }

    /**
     * 背景確認數檢查
     */
    private void startConfirmationChecker(String chainName, Web3j http, int requiredConfirmations) {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    checkConfirmations(chainName, http, requiredConfirmations);
                    Thread.sleep(5000);
                } catch (Exception ex) {
                    log.error("[{}] 確認數檢查錯誤: {}", chainName, ex.getMessage());
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 檢查指定鏈的待確認交易
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
