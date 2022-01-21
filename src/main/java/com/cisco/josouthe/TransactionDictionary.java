package com.cisco.josouthe;

import com.appdynamics.agent.api.ExitCall;
import com.appdynamics.agent.api.Transaction;

import java.util.Date;

public class TransactionDictionary {
    private Transaction appdTransaction = null;
    private String key;
    private Long lastTouchTime = null;
    private boolean finished = false;

    private static Long now() {
        return new Date().getTime();
    }

    public TransactionDictionary(String key, Transaction appTransaction) {
        this.key = key;
        this.appdTransaction = appTransaction;
        update();
    }

    public boolean isFinished() { return finished; }
    public void finish() { finished = true; }

    public void update() {
        lastTouchTime = now();
    }

    public String getKey() { return this.key; }

    public Transaction getTransaction() {
        this.update();
        return appdTransaction;
    }

    public void setTransaction(Transaction transaction) {
        this.update();
        this.appdTransaction = transaction;
    }

    public Long getLastTouchTime() {
        return this.lastTouchTime;
    }

}
