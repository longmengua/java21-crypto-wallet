package com.example.demo.dao;

import com.example.demo.constant.DepositStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Entity
@Table(name = "deposits", indexes = {@Index(name = "idx_txhash", columnList = "txHash", unique = true)})
public class Deposit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String txHash;

    private String userAddress;
    private String monitoredAddress;
    private String chain;
    private String tokenAddress; // null = native

    @Column(precision = 38, scale = 18)
    private BigDecimal amount;

    private String asset;
    private long blockNumber;
    private int decimals;
    private long txBlock;

    // -----------------------
    // 改成 enum
    // -----------------------
    @Enumerated(EnumType.STRING)
    private DepositStatus status; // UNCONFIRMED / CONFIRMING / CONFIRMED

    private int confirmations;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = Instant.now();
    }
}
