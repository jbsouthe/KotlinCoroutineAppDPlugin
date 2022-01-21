package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class KotlinAbstractCoroutineInterceptor extends MyBaseInterceptor{
    private static final ConcurrentHashMap<String, TransactionDictionary> transactionsMap = new ConcurrentHashMap<>();
    private final Scheduler scheduler;
    private IReflector getContext, cancellationExceptionMessage, getCoroutineName; //AbstractCoroutine

    public KotlinAbstractCoroutineInterceptor() {
        super();
        scheduler = Scheduler.getInstance(10000L, 60000L, transactionsMap, getLogger());

        /* @NotNull
            public final CoroutineContext getContext() {
                return this.context;
            }
         */
        getContext = makeInvokeInstanceMethodReflector("getContext"); //CoroutineContext

        /* @Override
            @NotNull
            protected String cancellationExceptionMessage() {
                return Intrinsics.stringPlus((String)DebugStringsKt.getClassSimpleName(this), (Object)" was cancelled");
            }
         */
        cancellationExceptionMessage = makeInvokeInstanceMethodReflector("cancellationExceptionMessage"); //String

        /* @Override
            @NotNull
            public String nameString$kotlinx_coroutines_core() {
                String string = CoroutineContextKt.getCoroutineName(this.context);
                if (string == null) {
                    return super.nameString$kotlinx_coroutines_core();
                }
                String coroutineName = string;
                return '\"' + coroutineName + "\":" + super.nameString$kotlinx_coroutines_core();
            }
         */
        getCoroutineName = makeInvokeInstanceMethodReflector("nameString$kotlinx_coroutines_core"); //String
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<Rule>();
        //https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/AbstractCoroutine.kt
        //protected void onCompleted(T value) {}
        rules.add( new Rule.Builder("kotlinx.coroutines.AbstractCoroutine")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString("onCompleted")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .build()
        );
        // protected void onCancelled(@NotNull Throwable cause, boolean handled) {}
        rules.add( new Rule.Builder("kotlinx.coroutines.AbstractCoroutine")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString("onCancelled")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .build()
        );
        /*public AbstractCoroutine(@NotNull CoroutineContext parentContext, boolean initParentJob, boolean active) {
            super(active);
            if (initParentJob) {
                this.initParentJob((Job)parentContext.get((CoroutineContext.Key)Job.Key));
            }
            this.context = parentContext.plus((CoroutineContext)this);
        }
         */
        rules.add( new Rule.Builder("kotlinx.coroutines.AbstractCoroutine")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString("<init>")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .build()
        );
        /*public final <R> void start(@NotNull CoroutineStart start, R receiver, @NotNull Function2<? super R, ? super Continuation<? super T>, ? extends Object> block) {
            start.invoke(block, receiver, this);
        } */
        rules.add( new Rule.Builder("kotlinx.coroutines.AbstractCoroutine")
                .classMatchType(SDKClassMatchType.INHERITS_FROM_CLASS)
                .methodMatchString("start")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .build()
        );
        return rules;
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        getLogger().debug(String.format("onMethodBegin starting for %s %s.%s()", objectIntercepted.toString(), className, methodName));
        Transaction transaction = AppdynamicsAgent.getTransaction(); //naively grab an active BT on this thread, we expect this to be noop

        switch(methodName) {
            case "<init>": { //during init, constructor, we assume a BT is running, if not we start one, and then mark a handoff on this new object
                if( isFakeTransaction(transaction) ) { //this is a noop transaction, so we need to start a BT, one is not already running
                    transaction = AppdynamicsAgent.startTransaction("CoroutineTransaction-placeholder", null, EntryTypes.POJO, true); //placeholder, we should try and configure a servlet bt for this transaction
                    getLogger().debug(String.format("Business Transaction was not running CoroutineTransaction-placeholder(%s) started for %s.%s( %s, %s )", transaction.getUniqueIdentifier(), className, methodName, params[0], params[1]));
                }

                if( isFakeTransaction(transaction) ) { //if the BT is still not started/real, we need to log it and abandon
                    getLogger().debug(String.format("Business Transaction is not running and could not be started for %s.%s( %s, %s )", className, methodName, params[0], params[1]));
                    return null;
                }
                //Object coroutineContext = getReflectiveObject(objectIntercepted, getContext); // this is the context that is being handed off to another thread, https://github.com/Kotlin/kotlinx.coroutines/blob/master/kotlinx-coroutines-core/common/src/AbstractCoroutine.kt
                transaction.markHandoff(objectIntercepted); //this lets the agent know that we are handing off a segment to another thread of execution, which is what dispatch does sooner or later
                getLogger().debug(String.format("Transaction markHandoff initiated for guid: '%s' isAsync Flag: %s Common Object: %s", transaction.getUniqueIdentifier(), transaction.isAsyncTransaction(), objectIntercepted));

                transaction = AppdynamicsAgent.startSegment(objectIntercepted); //start a Segment of the BT that marked this object for handoff earlier

                if (isFakeTransaction(transaction)) { //this object was not marked for handoff? log it
                    getLogger().debug(String.format("We intercepted an implementation of an AbstractCoroutine that was not marked for handoff? %s %s.%s()", objectIntercepted, className, methodName));
                } else { //this is what we hope for, and means we are starting a segment of a BT after an async handoff
                    getLogger().debug(String.format("We intercepted an implementation of an AbstractCoroutine that was marked for handoff! %s transaction segment guid: %s, %s %s.%s()",  objectIntercepted, transaction.getUniqueIdentifier(), objectIntercepted, className, methodName));
                }
                transactionsMap.put(generateKey(objectIntercepted), new TransactionDictionary(generateKey(objectIntercepted), transaction));
                break;
            }
            case "start": { //once start method is executed, we begin processing this coroutine, so we want to start the segment here and store it for the callback on finish
                /*
                if (!isFakeTransaction(transaction)) { //if it isn't noop we need to log it and figure out why
                    getLogger().debug(String.format("Coroutine Interceptor was expecting a noop, unless thread correlation is now working, active BT is: %s for %s.%s()", transaction.getUniqueIdentifier(), className, methodName));
                    //return null;
                }
                transaction = AppdynamicsAgent.startSegment(objectIntercepted); //start a Segment of the BT that marked this object for handoff earlier
                String coroutineName = getReflectiveString(objectIntercepted, getCoroutineName, "UNKNOWN-COROUTINE");
                collectSnapshotData(transaction, "Coroutines-Executed", coroutineName);
                if (isFakeTransaction(transaction)) { //this object was not marked for handoff? log it
                    getLogger().debug(String.format("We intercepted an implementation of an AbstractCoroutine that was not marked for handoff? %s %s %s.%s()", coroutineName, objectIntercepted, className, methodName));
                } else { //this is what we hope for, and means we are starting a segment of a BT after an async handoff
                    getLogger().debug(String.format("We intercepted an implementation of an AbstractCoroutine that was marked for handoff! coroutine: %s transaction segment guid: %s, %s %s.%s()", coroutineName, transaction.getUniqueIdentifier(), objectIntercepted, className, methodName));
                }
                transactionsMap.put(generateKey(objectIntercepted), new TransactionDictionary(generateKey(objectIntercepted), transaction));
                 */
                break;
            }
            case "onCancelled":
            case "onCompleted": { //when a coroutine is finished, it will call one of these two methods
                String coroutineName = getReflectiveString(objectIntercepted, getCoroutineName, "UNKNOWN-COROUTINE");
                TransactionDictionary transactionDictionary = transactionsMap.get(generateKey(objectIntercepted));
                if( transactionDictionary == null ) {
                    getLogger().debug(String.format("Oops, intercepted an %s but did not have this CoroutineContext in the Transaction Dictionary, for object %s with key %s",methodName,objectIntercepted, generateKey(objectIntercepted)));
                    return null;
                } else {
                    transaction = transactionDictionary.getTransaction();
                    collectSnapshotData(transaction, "Coroutines-Executed", coroutineName);
                    getLogger().debug(String.format("We intercepted an implementation of an AbstractCoroutine that was marked for handoff! coroutine: %s transaction segment guid: %s, %s %s.%s()", coroutineName, transaction.getUniqueIdentifier(), objectIntercepted, className, methodName));
                }
                break;
            }
        }
        getLogger().debug(String.format("onMethodBegin ending for %s.%s()", className, methodName));
        return transaction;
    }

    @Override
    public void onMethodEnd(Object state, Object objectIntercepted, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        if( state == null ) return;
        Transaction transaction = (Transaction) state;
        if( exception != null ) {
            transaction.markAsError(exception.toString());
        }
        switch (methodName) {
            case "onCancelled": {
                String cancellationExceptionMessageString = getReflectiveString(objectIntercepted,cancellationExceptionMessage,String.format("Coroutine %s, was cancelled", objectIntercepted));
                Throwable exceptionParameter = (Throwable) params[0];
                Boolean handledParameter = (Boolean) params[1];
                transaction.markAsError(String.format("Coroutine Cancellation Exception Message: %s", cancellationExceptionMessageString));
                transaction.markAsError(String.format("Coroutine Cancellation Cause Exception: %s handled: %s", exceptionParameter.toString(), handledParameter));
                //continue to onCompleted
            }
            case "onCompleted": {
                transaction.endSegment(); //end this segment whether cancelled or completed
                TransactionDictionary transactionDictionary = transactionsMap.get(generateKey(objectIntercepted));
                if( transactionDictionary != null ) {
                    transactionDictionary.finish();
                }
                getLogger().debug(String.format("Made it to the end of an entire segment, and ended it"));
                break;
            }
        }
    }

    private String generateKey( Object objectIntercepted ) {
        String key = String.valueOf(objectIntercepted);
        if( key.contains("}@") ) {
            key = key.split("@")[1];
        }
        return key;
    }
}
