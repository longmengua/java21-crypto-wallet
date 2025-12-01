package com.example.demo.service.notifier;

import com.example.demo.dao.Deposit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class NotifierConsole implements Notifier {
    @Override
    public void onNewDeposit(Deposit deposit) {
        log.info("onNewDeposit: {}", deposit);
    }

    @Override
    public void onDepositConfirmed(Deposit deposit) {
        log.info("onDepositConfirmed: {}", deposit);
    }
}
