package com.cisco.josouthe;

import com.appdynamics.agent.api.ExitCall;
import com.appdynamics.agent.api.Transaction;

import java.util.Date;

public class TransactionDictionary {
    private Transaction appdTransaction = null;
    private ExitCall appdExitCall = null;
    public Object futureTask = null;
    private Long lastTouchTime = null;
    private boolean finished = false;

    private static Long now() {
        return new Date().getTime();
    }

    public TransactionDictionary(Transaction appTransaction, Object futureTask) {
        this.appdTransaction = appTransaction;
        this.futureTask = futureTask;
        update();
    }

    public TransactionDictionary(ExitCall appdExitCall, Object futureTask) {
        this.appdExitCall = appdExitCall;
        this.futureTask = futureTask;
        update();
    }

    public boolean isFinished() { return finished; }
    public void finish() { finished = true; }

    public void update() {
        lastTouchTime = now();
    }

    public Transaction getTransaction() {
        this.update();
        return appdTransaction;
    }

    public void setTransaction(Transaction transaction) {
        this.update();
        this.appdTransaction = transaction;
    }

    public ExitCall getExitCall() {
        this.update();
        return appdExitCall;
    }

    public void setExitCall(ExitCall appdExitCall) {
        this.update();
        this.appdExitCall = appdExitCall;
    }

    public Long getLastTouchTime() {
        return this.lastTouchTime;
    }

}
