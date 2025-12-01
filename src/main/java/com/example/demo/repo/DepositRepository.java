package com.example.demo.repo;

import com.example.demo.constant.DepositStatus;
import com.example.demo.dao.Deposit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DepositRepository extends JpaRepository<Deposit, Long> {
    List<Deposit> findByStatusIn(List<DepositStatus> statuses);
    Optional<Deposit> findByTxHash(String txHash);
}
