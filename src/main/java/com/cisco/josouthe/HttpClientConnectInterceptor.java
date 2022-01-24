package com.cisco.josouthe;

import com.appdynamics.agent.api.AppdynamicsAgent;
import com.appdynamics.agent.api.EntryTypes;
import com.appdynamics.agent.api.ServletContext;
import com.appdynamics.agent.api.Transaction;
import com.appdynamics.instrumentation.sdk.Rule;
import com.appdynamics.instrumentation.sdk.SDKClassMatchType;
import com.appdynamics.instrumentation.sdk.SDKStringMatchType;
import com.appdynamics.instrumentation.sdk.toolbox.reflection.IReflector;

import java.net.MalformedURLException;
import java.util.*;

public class HttpClientConnectInterceptor extends MyBaseInterceptor{

    IReflector methodAttr, toURI; //HttpClientConnect
    IReflector name; //HttpMethod
    IReflector toExternalForm; //UriEndpoint
    IReflector requestHeaders; //HttpClientOperations
    IReflector getAsString, names; //HttpHeaders

    public HttpClientConnectInterceptor() {
        super();

        methodAttr = makeAccessFieldValueReflector("method"); //HttpMethod
        name = makeInvokeInstanceMethodReflector("name"); //String
        toURI = makeAccessFieldValueReflector("toURI"); //UriEndpoint
        toExternalForm = makeInvokeInstanceMethodReflector("toExternalForm"); //String
        requestHeaders = makeInvokeInstanceMethodReflector("requestHeaders"); //returns HttpHeaders
        getAsString = makeInvokeInstanceMethodReflector("getAsString", CharSequence.class.getCanonicalName() ); //String
        names = makeInvokeInstanceMethodReflector("names"); //Set<String>
    }

    @Override
    public List<Rule> initializeRules() {
        List<Rule> rules = new ArrayList<Rule>();
        /*
        [reactor-http-epoll-3] 24 Jan 2022 18:05:39,944 DEBUG NettyV4ExitProducerInterceptor - No BT currentContext, try configuring a custom entry point.  Call chain:
java.lang.Exception: Stack trace for unconfigured transaction
	at com.appdynamics.appagent/com.singularity.ee.agent.appagent.services.transactionmonitor.common.activity.ActivityInProcessProducerInterceptor.setUpInProcessCorrelationInfo(ActivityInProcessProducerInterceptor.java:104)
	at com.appdynamics.appagent/com.singularity.ee.agent.appagent.services.transactionmonitor.AInProcessProducerInterceptor.onMethodBeginTracked(AInProcessProducerInterceptor.java:33)
	at com.appdynamics.appagent/com.singularity.ee.agent.appagent.services.transactionmonitor.netty.v4.NettyV4ExitProducerInterceptor.onMethodBeginTracked(NettyV4ExitProducerInterceptor.java:97)
	at com.appdynamics.appagent/com.singularity.ee.agent.appagent.services.bciengine.AFastTrackedMethodInterceptor.onMethodBegin(AFastTrackedMethodInterceptor.java:52)
	at com.appdynamics.appagent/com.singularity.ee.agent.appagent.kernel.bootimpl.FastMethodInterceptorDelegatorImpl.safeOnMethodBeginNoReentrantCheck(FastMethodInterceptorDelegatorImpl.java:370)
	at com.appdynamics.appagent/com.singularity.ee.agent.appagent.kernel.bootimpl.FastMethodInterceptorDelegatorImpl.safeOnMethodBegin(FastMethodInterceptorDelegatorImpl.java:295)
	at com.singularity.ee.agent.appagent.entrypoint.bciengine.FastMethodInterceptorDelegatorBoot.safeOnMethodBegin(FastMethodInterceptorDelegatorBoot.java:51)
	at reactor.netty.http.client.HttpClientConnect$HttpClientHandler.requestWithBody(HttpClientConnect.java)
	at reactor.netty.http.client.HttpClientConnect$HttpIOHandlerObserver.lambda$onStateChange$0(HttpClientConnect.java:423)
	at reactor.core.publisher.MonoDefer.subscribe(MonoDefer.java:44)
	at reactor.netty.http.client.HttpClientConnect$HttpIOHandlerObserver.onStateChange(HttpClientConnect.java:424)
	at reactor.netty.ReactorNetty$CompositeConnectionObserver.onStateChange(ReactorNetty.java:654)
	at reactor.netty.resources.DefaultPooledConnectionProvider$DisposableAcquire.run(DefaultPooledConnectionProvider.java:287)
	at io.netty.util.concurrent.AbstractEventExecutor.safeExecute(AbstractEventExecutor.java:164)
	at io.netty.util.concurrent.SingleThreadEventExecutor.runAllTasks(SingleThreadEventExecutor.java:469)
	at io.netty.channel.epoll.EpollEventLoop.run(EpollEventLoop.java:384)
	at io.netty.util.concurrent.SingleThreadEventExecutor$4.run(SingleThreadEventExecutor.java:986)
	at io.netty.util.internal.ThreadExecutorMap$2.run(ThreadExecutorMap.java:74)
	at io.netty.util.concurrent.FastThreadLocalRunnable.run(FastThreadLocalRunnable.java:30)
	at java.base/java.lang.Thread.run(Unknown Source)
         */
        rules.add( new Rule.Builder("reactor.netty.http.client.HttpClientConnect$HttpClientHandler")
                .classMatchType(SDKClassMatchType.MATCHES_CLASS)
                .methodMatchString("requestWithBody")
                .methodStringMatchType(SDKStringMatchType.EQUALS)
                .build()
        );
        return rules;
    }

    @Override
    public Object onMethodBegin(Object objectIntercepted, String className, String methodName, Object[] params) {
        Transaction transaction = AppdynamicsAgent.getTransaction();
        if( !isFakeTransaction(transaction) ) {
            getLogger().debug(String.format("BT already configured for %s.%s(), nothing more to do, this plugin may no longer be needed",className,methodName));
            return null;
        }
        Object httpClientOperation = params[0];
        try {
            transaction = AppdynamicsAgent.startServletTransaction( buildServletContext( objectIntercepted, httpClientOperation), EntryTypes.HTTP, getCorrelationHeader(httpClientOperation), false);
        } catch (MalformedURLException e) {
            getLogger().info(String.format("Unable to start a servlet BT for this request, falling back to POJO BT 'HttpClientConnect-placeholder', message: %s",e.getMessage()));
            transaction = AppdynamicsAgent.startTransaction("HttpClientConnect-placeholder", getCorrelationHeader(httpClientOperation), EntryTypes.POJO, false);
        }
        return transaction;
    }

    private ServletContext buildServletContext(Object httpClientConnect, Object httpClientOperation) throws MalformedURLException {
        ServletContext.ServletContextBuilder servletContextBuilder = new ServletContext.ServletContextBuilder();
        Object method = getReflectiveObject(httpClientConnect, methodAttr);
        servletContextBuilder.withRequestMethod( getReflectiveString(method,name,"GET"));
        Object uriEndpoint = getReflectiveObject( httpClientConnect, toURI);
        servletContextBuilder.withURL( getReflectiveString(uriEndpoint,toExternalForm,"http://localhost/unknown-uri")); //throws MalformedURLException
        Object headers = getReflectiveObject(httpClientOperation, requestHeaders);
        Set<String> headerNames = (Set<String>) getReflectiveObject(headers, names);
        if( headerNames != null ) {
            Map<String,String> appdHeaders = new HashMap<>();
            for( String headerName : headerNames ) {
                String value = (String) getReflectiveObject(headers, getAsString, headerName);
                if( value != null ) appdHeaders.put(headerName, value);
            }
            if( appdHeaders.size() > 0 )
                servletContextBuilder.withHeaders(appdHeaders);
        }
        return servletContextBuilder.build();
    }

    private String getCorrelationHeader(Object httpClientOperation) {
        Object headers = getReflectiveObject(httpClientOperation, requestHeaders);
        String correlationHeader = (String) getReflectiveObject(headers, getAsString, AppdynamicsAgent.TRANSACTION_CORRELATION_HEADER);
        getLogger().debug(String.format("Found a correlation header: '%s'",correlationHeader));
        return correlationHeader;
    }

    @Override
    public void onMethodEnd(Object state, Object objectIntercepted, String className, String methodName, Object[] params, Throwable exception, Object returnVal) {
        if( state == null ) return;
        Transaction transaction = (Transaction) state;
        if( exception != null )
            transaction.markAsError(exception.toString());
        transaction.end();
    }
}
