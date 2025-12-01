package com.example.demo.controller;

import com.example.demo.dao.Deposit;
import com.example.demo.repo.DepositRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/deposits")
public class DepositController {
    @Autowired
    private DepositRepository repo;

    @GetMapping
    public List<Deposit> list() {
        return repo.findAll();
    }
}
