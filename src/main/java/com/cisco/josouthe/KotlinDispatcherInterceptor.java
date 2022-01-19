package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;

import java.util.ArrayList;
import java.util.List;

public class KotlinDispatcherInterceptor extends MyBaseInterceptor{

    public KotlinDispatcherInterceptor() {
        super();

    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<Rule>();
        //https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/CoroutineDispatcher.kt
        rules.add( new Rule.Builder("kotlinx.coroutines.CoroutineDispatcher")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString("dispatch")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .build()
        );
        return rules;
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        getLogger().info(String.format("onMethodBegin starting for %s.%s( %s, %s )", className, methodName, params[0], params[1]));
        Transaction transaction = AppdynamicsAgent.getTransaction(); //the active BT
        if( isFakeTransaction(transaction) ) { //this is a noop transaction, so we need to start a BT, one is not already running
            transaction = AppdynamicsAgent.startTransaction("CoroutineTransaction-placeholder", null, EntryTypes.POJO, true); //placeholder, we should try and configure a servlet bt for this transaction
        }

        if( isFakeTransaction(transaction) ) { //if the BT is still not started/real, we need to log it and abandon
            getLogger().info(String.format("Business Transaction is not running and could not be started for %s.%s( %s, %s )", className, methodName, params[0], params[1]));
            return null;
        }
        Object coroutineContext = params[0]; // this is the context that is being handed off to another thread, https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/AbstractCoroutine.kt
        transaction.markHandoff(coroutineContext); //this lets the agent know that we are handing off a segment to another thread of execution, which is what dispatch does sooner or later
        getLogger().info(String.format("Transaction markHandoff initiated for guid: '%s' isAsync Flag: %s Common Object: %s", transaction.getUniqueIdentifier(), transaction.isAsyncTransaction(), coroutineContext));

        getLogger().info(String.format("onMethodBegin ending for %s.%s( %s, %s )", className, methodName, params[0], params[1]));
        return null;
    }

    @Override
    public void onMethodEnd(Object state, Object objectIntercepted, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {

    }
}
