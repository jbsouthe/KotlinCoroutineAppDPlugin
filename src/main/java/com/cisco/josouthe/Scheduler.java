package com.cisco.josouthe;

import com.appdynamics.instrumentation.sdk.logging.ISDKLogger;

import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;

public class Scheduler extends Thread {
    private static final String THREAD_NAME = "AppDynamics Transaction Cleaner Thread";
    ArrayList<ConcurrentHashMap<String, TransactionDictionary>> maps = new ArrayList<>();
    long sleepTime = 30000;
    long ageToDiscard = 120000;
    private static Scheduler instance = null;
    private ISDKLogger logger;

    public synchronized static Scheduler getInstance(long sleepTimeMS, long ageToDiscardMS, ConcurrentHashMap<String, TransactionDictionary> concurrentHashMap, ISDKLogger logger) {
        boolean start = false;
        if( instance == null ) {
            logger.debug(String.format("Scheduler Singleton for $s is being created", THREAD_NAME));
            instance = new Scheduler(logger);
            start = true;
        }
        if( sleepTimeMS > 30000 ) instance.sleepTime = sleepTimeMS; //safety check, we aren't going faster than this
        if( ageToDiscardMS < instance.ageToDiscard ) instance.ageToDiscard = ageToDiscardMS; //safety check, we aren't keeping longer than this
        instance.maps.add(concurrentHashMap);
        if( start ) instance.start();
        return instance;
    }

    private Scheduler( ISDKLogger logger ) {
        setDaemon(true);
        try {
            setPriority( (int)getPriority()/2 );
        } catch (Exception e) {
            //we tried, no op
        }
        setName(THREAD_NAME);
        this.logger = logger;
    }

    /**
     * When an object implementing interface <code>Runnable</code> is used
     * to create a thread, starting the thread causes the object's
     * <code>run</code> method to be called in that separately executing
     * thread.
     * <p>
     * The general contract of the method <code>run</code> is that it may
     * take any action whatsoever.
     *
     * @see Thread#run()
     */
    @Override
    public void run() {
        while(true) {
            for( ConcurrentHashMap<String, TransactionDictionary> map : maps ) {
                long now = new Date().getTime();
                long numTransactions = map.size();
                long numRemoved = 0;
                for (TransactionDictionary transactionDictionary : map.values()) {
                    if( transactionDictionary.isFinished() || now > (transactionDictionary.getLastTouchTime() + ageToDiscard) ) {
                        numRemoved++;
                        map.remove( transactionDictionary.getKey() );
                    }
                }
                logger.debug(String.format("Scheduler examined map:%s with %d map entries and removed %d stale or completed transaction segments", map.toString(), numTransactions, numRemoved));
            }
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                //no op
            }
        }
    }
}