package com.ldbc.driver.runtime.metrics;

import com.ldbc.driver.Operation;
import com.ldbc.driver.runtime.ConcurrentErrorReporter;
import com.ldbc.driver.runtime.metrics.sbe.MetricsEvent;
import com.ldbc.driver.temporal.TimeSource;
import com.ldbc.driver.util.csv.SimpleCsvFileWriter;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import uk.co.real_logic.sbe.codec.java.DirectBuffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.locks.LockSupport;

import static com.ldbc.driver.runtime.metrics.DisruptorSbeMetricsEvent.*;

public class DisruptorSbeMetricsService implements ConcurrentMetricsService {
    private static final long SHUTDOWN_WAIT_TIMEOUT_AS_MILLI = TimeUnit.SECONDS.toMillis(5);

    // TODO this could come from config, if we had a max_runtime parameter. for now, it can default to something
    public static final long DEFAULT_HIGHEST_EXPECTED_RUNTIME_DURATION_AS_NANO = TimeUnit.MINUTES.toNanos(90);

    private final AtomicLong initiatedEvents = new AtomicLong(0);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final TimeSource timeSource;
    private final RingBuffer<DirectBuffer> ringBuffer;
    private final Disruptor<DirectBuffer> disruptor;
    private final DisruptorSbeMetricsEventHandler eventHandler;
    private final List<DisruptorSbeConcurrentMetricsServiceWriter> metricsServiceWriters;

    public DisruptorSbeMetricsService(TimeSource timeSource,
                                      ConcurrentErrorReporter errorReporter,
                                      TimeUnit timeUnit,
                                      long maxRuntimeDurationAsNano,
                                      SimpleCsvFileWriter csvResultsLogWriter,
                                      Map<Integer, Class<? extends Operation<?>>> operationTypeToClassMapping) throws MetricsCollectionException {
        // Specify the size of the ring buffer, must be power of 2
        int bufferSize = 1024;

        // Construct the Disruptor
        disruptor = new Disruptor(
                new MetricsCollectionEventFactory(),
                bufferSize,
                // Executor that will be used to construct new threads for consumers
                Executors.newSingleThreadExecutor(),
                ProducerType.MULTI,
                new BlockingWaitStrategy()
//                new LiteBlockingWaitStrategy()
//                new SleepingWaitStrategy()
//                new YieldingWaitStrategy()
//                new BusySpinWaitStrategy()
//                new TimeoutBlockingWaitStrategy()
//                new PhasedBackoffWaitStrategy()
        );

        // Connect the handler
        eventHandler = new DisruptorSbeMetricsEventHandler(
                errorReporter,
                csvResultsLogWriter,
                timeUnit,
                timeSource,
                maxRuntimeDurationAsNano,
                operationTypeToClassMapping
        );

        disruptor.handleEventsWith(eventHandler);
        DisruptorExceptionHandler exceptionHandler = new DisruptorExceptionHandler(errorReporter);
        disruptor.handleExceptionsFor(eventHandler).with(exceptionHandler);
        disruptor.handleExceptionsWith(exceptionHandler);

        // Start the Disruptor, starts all threads running  & get the ring buffer from the Disruptor to be used for publishing
        ringBuffer = disruptor.start();

        this.timeSource = timeSource;
        metricsServiceWriters = new ArrayList<>();
    }

    @Override
    synchronized public void shutdown() throws MetricsCollectionException {
        if (shutdown.get()) {
            throw new MetricsCollectionException("Metrics service has already been shutdown");
        }
        long startTimeMs = timeSource.nowAsMilli();
        boolean shutdownSuccessful = false;
        while (timeSource.nowAsMilli() - startTimeMs < SHUTDOWN_WAIT_TIMEOUT_AS_MILLI) {
            if (eventHandler.processedEventCount() >= initiatedEvents.get()) {
                shutdownSuccessful = true;
                break;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
        if (false == shutdownSuccessful) {
            String errMsg = String.format("%s timed out waiting for last operations to complete\n%s/%s operations completed",
                    getClass().getSimpleName(),
                    eventHandler.processedEventCount(),
                    initiatedEvents.get()
            );
            throw new MetricsCollectionException(errMsg);
        }
        try {
            disruptor.shutdown(SHUTDOWN_WAIT_TIMEOUT_AS_MILLI, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            String errMsg = String.format("%s timed out waiting for %s to shutdown",
                    getClass().getSimpleName(),
                    disruptor.getClass().getSimpleName()
            );
            throw new MetricsCollectionException(errMsg, e);
        }
        AlreadyShutdownPolicy alreadyShutdownPolicy = new AlreadyShutdownPolicy();
        for (DisruptorSbeConcurrentMetricsServiceWriter metricsServiceWriter : metricsServiceWriters) {
            metricsServiceWriter.setAlreadyShutdownPolicy(alreadyShutdownPolicy);
        }
        shutdown.set(true);
    }

    @Override
    public ConcurrentMetricsServiceWriter getWriter() throws MetricsCollectionException {
        if (shutdown.get()) {
            throw new MetricsCollectionException("Metrics service has already been shutdown");
        }
        DisruptorSbeConcurrentMetricsServiceWriter metricsServiceWriter =
                new DisruptorSbeConcurrentMetricsServiceWriter(initiatedEvents, ringBuffer, eventHandler);
        metricsServiceWriters.add(metricsServiceWriter);
        return metricsServiceWriter;
    }

    private static class DisruptorSbeConcurrentMetricsServiceWriter implements ConcurrentMetricsServiceWriter {
        private final AtomicLong initiatedEvents;
        private final RingBuffer<DirectBuffer> ringBuffer;
        private final DisruptorSbeMetricsEventHandler eventHandler;
        private final SubmitOperationResultTranslator submitOperationResultTranslator;
        private final GetWorkloadStatusTranslator getWorkloadStatusTranslator;
        private final GetWorkloadResultsTranslator getWorkloadResultsTranslator;

        private AlreadyShutdownPolicy alreadyShutdownPolicy = null;

        public DisruptorSbeConcurrentMetricsServiceWriter(AtomicLong initiatedEvents,
                                                          RingBuffer<DirectBuffer> ringBuffer,
                                                          DisruptorSbeMetricsEventHandler eventHandler) {
            this.initiatedEvents = initiatedEvents;
            this.ringBuffer = ringBuffer;
            this.eventHandler = eventHandler;
            MetricsEvent metricsEvent = new MetricsEvent();
            this.submitOperationResultTranslator = new SubmitOperationResultTranslator(metricsEvent);
            this.getWorkloadStatusTranslator = new GetWorkloadStatusTranslator(metricsEvent);
            this.getWorkloadResultsTranslator = new GetWorkloadResultsTranslator(metricsEvent);
        }

        private void setAlreadyShutdownPolicy(AlreadyShutdownPolicy alreadyShutdownPolicy) {
            this.alreadyShutdownPolicy = alreadyShutdownPolicy;
        }

        @Override
        public void submitOperationResult(int operationType,
                                          long scheduledStartTimeAsMilli,
                                          long actualStartTimeAsMilli,
                                          long runDurationAsNano,
                                          int resultCode) throws MetricsCollectionException {
            if (null != alreadyShutdownPolicy) {
                alreadyShutdownPolicy.apply();
            }
            initiatedEvents.incrementAndGet();
            ringBuffer.publishEvent(submitOperationResultTranslator, operationType, scheduledStartTimeAsMilli, actualStartTimeAsMilli, runDurationAsNano, resultCode);
        }

        @Override
        public WorkloadStatusSnapshot status() throws MetricsCollectionException {
            if (null != alreadyShutdownPolicy) {
                alreadyShutdownPolicy.apply();
            }
            AtomicStampedReference<WorkloadStatusSnapshot> statusSnapshotReference = eventHandler.statusSnapshot();
            int oldStamp = statusSnapshotReference.getStamp();
            ringBuffer.publishEvent(getWorkloadStatusTranslator);
            while (statusSnapshotReference.getStamp() <= oldStamp) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            }
            return statusSnapshotReference.getReference();
        }

        @Override
        public WorkloadResultsSnapshot results() throws MetricsCollectionException {
            if (null != alreadyShutdownPolicy) {
                alreadyShutdownPolicy.apply();
            }
            AtomicStampedReference<WorkloadResultsSnapshot> resultsSnapshotReference = eventHandler.resultsSnapshot();
            int oldStamp = resultsSnapshotReference.getStamp();
            ringBuffer.publishEvent(getWorkloadResultsTranslator);
            while (resultsSnapshotReference.getStamp() <= oldStamp) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            }
            return resultsSnapshotReference.getReference();
        }

        public static class SubmitOperationResultTranslator implements EventTranslatorVararg<DirectBuffer> {
            private final MetricsEvent metricsEvent;

            public SubmitOperationResultTranslator(MetricsEvent metricsEvent) {
                this.metricsEvent = metricsEvent;
            }

            @Override
            public void translateTo(DirectBuffer event, long l, Object... fields) {
                metricsEvent.wrapForEncode(event, MESSAGE_HEADER_SIZE)
                        .eventType(SUBMIT_OPERATION_RESULT)
                        .operationType((int) fields[0])
                        .scheduledStartTimeAsMilli((long) fields[1])
                        .actualStartTimeAsMilli((long) fields[2])
                        .runDurationAsNano((long) fields[3])
                        .resultCode((int) fields[4]);
            }
        }

        public static class GetWorkloadStatusTranslator implements EventTranslator<DirectBuffer> {
            private final MetricsEvent metricsEvent;

            public GetWorkloadStatusTranslator(MetricsEvent metricsEvent) {
                this.metricsEvent = metricsEvent;
            }

            @Override
            public void translateTo(DirectBuffer event, long l) {
                metricsEvent.wrapForEncode(event, MESSAGE_HEADER_SIZE)
                        .eventType(GET_WORKLOAD_STATUS);
            }
        }

        public static class GetWorkloadResultsTranslator implements EventTranslator<DirectBuffer> {
            private final MetricsEvent metricsEvent;

            public GetWorkloadResultsTranslator(MetricsEvent metricsEvent) {
                this.metricsEvent = metricsEvent;
            }

            @Override
            public void translateTo(DirectBuffer event, long l) {
                metricsEvent.wrapForEncode(event, MESSAGE_HEADER_SIZE)
                        .eventType(GET_WORKLOAD_RESULTS);
            }
        }
    }

    private static class AlreadyShutdownPolicy {
        void apply() throws MetricsCollectionException {
            throw new MetricsCollectionException("Metrics service has already been shutdown");
        }
    }
}