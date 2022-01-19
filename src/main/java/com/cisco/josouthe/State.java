package com.cisco.josouthe;

import com.appdynamics.agent.api.ExitCall;
import com.appdynamics.agent.api.Transaction;

public class State {
    public State(Transaction t, ExitCall e ) {
        this.transaction = t;
        this.exitCall = e;
    }
    public Transaction transaction;
    public ExitCall exitCall;
}
