package com.finbridge.paymenthub.integration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Configuration
@EnableScheduling
class IntegrationScheduling {}

@Component
@ConditionalOnProperty(name="finbridge.provider.worker-enabled",havingValue="true")
public class IntegrationWorker {
    private final OutboxDispatcher dispatcher;
    private final ProviderCallbacks callbacks;
    private final io.micrometer.core.instrument.MeterRegistry metrics;
    public IntegrationWorker(OutboxDispatcher dispatcher,ProviderCallbacks callbacks,io.micrometer.core.instrument.MeterRegistry metrics){this.dispatcher=dispatcher;this.callbacks=callbacks;this.metrics=metrics;}
    @Scheduled(fixedDelayString="${finbridge.provider.poll-ms:1000}")
    public void tick(){
        if(!callbacks.configured())return;
        try {for(int n=0;n<20 && metrics.timer("finbridge.outbox.dispatch.duration").record(()->dispatcher.dispatchOne(false));n++){} for(int n=0;n<20 && metrics.timer("finbridge.provider.callback.duration").record(()->dispatcher.deliverOne());n++){} }
        catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger(getClass()).error("Outbox worker failed; transaction retained for retry: {}",e.getClass().getSimpleName());}
    }
}
