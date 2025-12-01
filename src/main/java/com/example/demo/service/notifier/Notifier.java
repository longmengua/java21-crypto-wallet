package com.example.demo.service.notifier;

import com.example.demo.dao.Deposit;

public interface Notifier {
    void onNewDeposit(Deposit deposit);      // e.g. push to queue / websocket
    void onDepositConfirmed(Deposit deposit);
}

