package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;

import java.util.ArrayList;
import java.util.List;

//This may only be measuring the time to run onCompleted and onCancelled, which isn't the total time, we would need to
// watch for the onStart and map that in our transaction dictionary, then complete from there, but it is a start
public class KotlinAbstractCoroutineInterceptor extends MyBaseInterceptor{

    public KotlinAbstractCoroutineInterceptor() {
        super();
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<Rule>();
        //https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/AbstractCoroutine.kt
        rules.add( new Rule.Builder("kotlinx.coroutines.AbstractCoroutine")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString("onCompleted")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .build()
        );
        rules.add( new Rule.Builder("kotlinx.coroutines.AbstractCoroutine")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString("onCancelled")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .build()
        );
        return rules;
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        getLogger().info(String.format("onMethodBegin starting for %s.%s()", className, methodName));

        Transaction transaction = AppdynamicsAgent.getTransaction(); //naively grab an active BT on this thread, we expect this to be noop
        if( !isFakeTransaction(transaction) ) { //if it isn't noop we need to log it and figure out why
            getLogger().info(String.format("Coroutine Interceptor was expecting a noop, unless thread correlation is now working, active BT is: %s for %s.%s()", transaction.getUniqueIdentifier(), className, methodName));
            return null;
        }

        transaction = AppdynamicsAgent.startSegment(objectIntercepted); //start a Segment of the BT that marked this object for handoff earlier
        if( isFakeTransaction(transaction) ) { //this object was not marked for handoff? log it
            getLogger().info(String.format("We intercepted an implementation of an AbstractCoroutine that was not marked for handoff? %s %s.%s()", objectIntercepted, className, methodName));
        } else { //this is what we hope for, and means we are starting a segment of a BT after an async handoff
            getLogger().info(String.format("We intercepted an implementation of an AbstractCoroutine that was marked for handoff! transaction segment guid: %s, %s %s.%s()", transaction.getUniqueIdentifier(), objectIntercepted, className, methodName));
        }
        getLogger().info(String.format("onMethodBegin ending for %s.%s()", className, methodName));
        return transaction;
    }

    @Override
    public void onMethodEnd(Object state, Object objectIntercepted, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        if( state == null ) return;
        Transaction transaction = (Transaction) state;
        if( exception != null ) {
            transaction.markAsError(exception.toString());
        }
        if( methodName.equals("onCancelled")) {
            transaction.markAsError(String.format("Coroutine %s, was cancelled", objectIntercepted));
        }
        transaction.endSegment(); //end this segment whether cancelled or completed
        getLogger().info(String.format("Made it to the end of an entire segment, and ended it"));
    }
}
