package fi.nls.oskari.work.hystrix;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.netflix.hystrix.HystrixInvokable;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.executionhook.HystrixCommandExecutionHook;
import fi.nls.oskari.log.LogFactory;
import fi.nls.oskari.log.Logger;
import fi.nls.oskari.work.hystrix.metrics.AvgJobLengthGauge;
import fi.nls.oskari.work.hystrix.metrics.MaxJobLengthGauge;
import fi.nls.oskari.work.hystrix.metrics.MinJobLengthGauge;
import fi.nls.oskari.work.hystrix.metrics.TimingGauge;
import fi.nls.oskari.worker.Job;
import fi.nls.oskari.worker.JobQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Adds support for Hystrix commands, defaults to internal threading solution for non-Hystrix jobs
 */
public class HystrixJobQueue extends JobQueue {
    private static final Logger log = LogFactory.getLogger(HystrixJobQueue.class);
    private Map<String, Future<String>> commandsMapping = new ConcurrentHashMap<String, Future<String>>(100);
    private MetricRegistry metrics = new MetricRegistry();

    private long mapSize = 0;
    private Map<String, TimingGauge> customMetrics = new ConcurrentHashMap<String, TimingGauge>();

    public HystrixJobQueue(int nWorkers) {
        super(nWorkers);

        HystrixPlugins.getInstance().registerCommandExecutionHook(new HystrixCommandExecutionHook() {
            /**
             * Actual run method execution starts
             * @param commandInstance
             * @param <T>
             */
            @Override
            public <T> void onExecutionStart(HystrixInvokable<T> commandInstance) {
                if(commandInstance instanceof HystrixMapLayerJob) {
                    HystrixMapLayerJob job = (HystrixMapLayerJob) commandInstance;
                    job.setStartTime();
                    job.notifyStart();
                }
                super.onExecutionStart(commandInstance);
            }

            /**
             * Actual run method completed successfully
             * @param commandInstance
             * @param <T>
             */
            @Override
            public <T> void onExecutionSuccess(HystrixInvokable<T> commandInstance) {
                if(commandInstance instanceof HystrixJob) {
                    HystrixJob job = (HystrixJob)commandInstance;
                    jobEnded(job, true, "Job completed", job.getKey());
                }
                super.onExecutionSuccess(commandInstance);
            }

            /**
             * When command fails with an exception
             * @param commandInstance
             * @param failureType
             * @param e
             * @param <T>
             * @return
             */
            @Override
            public <T> Exception onError(HystrixInvokable<T> commandInstance, HystrixRuntimeException.FailureType failureType, Exception e) {
                if(commandInstance instanceof HystrixJob) {
                    HystrixJob job = (HystrixJob)commandInstance;
                    jobEnded(job, false, "Error on job", job.getKey(), failureType, e.getMessage());
                }
                return super.onError(commandInstance, failureType, e);
            }

            /**
             * Fallback is an error handler so treat as error
             * @param commandInstance
             * @param <T>
             */
            @Override
            public <T> void onFallbackSuccess(HystrixInvokable<T> commandInstance) {
                if(commandInstance instanceof HystrixJob) {
                    HystrixJob job = (HystrixJob)commandInstance;
                    jobEnded(job, false, "Job fallback", job.getKey());
                }
                super.onFallbackSuccess(commandInstance);
            }

        });
    }

    /**
     * Clean up and log.warn if there was an error
     * @param job
     * @param success
     * @param args
     */
    private void jobEnded(HystrixJob job, boolean success, Object... args) {
        if(success) {
            log.debug(args);
        }
        else {
            log.warn(args);
        }

        // NOTE! job.getExecutionTimeInMilliseconds() doesn't seem to provide correct values
        // maybe because we use futures/queue() instead of execute()?
        final long runtimeMS = (System.nanoTime() - job.getStartTime()) / 1000000L;
        // statistics
        if(job instanceof HystrixMapLayerJob) {
            HystrixMapLayerJob mlJob = (HystrixMapLayerJob) job;
            mlJob.notifyCompleted(success);
            final String jobId = getJobId(mlJob);
            final Histogram timing = metrics.histogram(
                    MetricRegistry.name(HystrixMapLayerJob.class, "exec.time." + jobId));
            timing.update(runtimeMS);

            TimingGauge gauge = customMetrics.get(jobId);
            if(gauge == null) {
                gauge = new TimingGauge();
                customMetrics.put(jobId, gauge);
                // first run
                metrics.register(MetricRegistry.name(HystrixJobQueue.class, "job.length.max." + jobId), new MaxJobLengthGauge(gauge));
                metrics.register(MetricRegistry.name(HystrixJobQueue.class, "job.length.min." + jobId), new MinJobLengthGauge(gauge));
                metrics.register(MetricRegistry.name(HystrixJobQueue.class, "job.length.avg." + jobId), new AvgJobLengthGauge(gauge));
            }
            log.debug("Job completed in", runtimeMS);
            gauge.setupTimingStatistics(runtimeMS);

            if(!success) {
                final Counter failCounter = metrics.counter(
                        MetricRegistry.name(HystrixJobQueue.class, "jobs.fails." + jobId));
                failCounter.inc();
            }
        }
        setupTimingStatistics(runtimeMS);
        job.teardown();
        // jobs stick around for some reason, clean the map when job has ended
        cleanup(false);
    }

    private String getJobId(HystrixMapLayerJob mlJob) {
        return mlJob.layerId + "." + mlJob.type.toString();
    }

    public MetricRegistry getMetricsRegistry() {
        return metrics;
    }

    public void cleanup(boolean force) {
        List<String> doneJobs = new ArrayList<String>();
        for(Map.Entry<String, Future<String>> entry : commandsMapping.entrySet()) {
            if(entry.getValue().isDone()) {
                doneJobs.add(entry.getKey());
            } else if(force) {
                entry.getValue().cancel(true);
                doneJobs.add(entry.getKey());
            }
        }
        for(String key : doneJobs) {
            commandsMapping.remove(key);
        }
    }

    public long getQueueSize() {
        return super.getQueueSize() + commandsMapping.size();
    }

    public long getMaxQueueLength() {
        return super.getMaxQueueLength() + mapSize;
    }

    public List<String> getQueuedJobNames() {
        final List<String> names = super.getQueuedJobNames();
        names.addAll(commandsMapping.keySet());
        return names;
    }
    /**
     * Custom handling for HystrixJobs, call super on other type of jobs
     * @param job
     */
    public void add(Job job) {
        // removed previous job with same key
        remove(job);
        if(job instanceof HystrixJob) {
            if(job instanceof HystrixMapLayerJob) {
                HystrixMapLayerJob mlJob = (HystrixMapLayerJob) job;
                Meter addMeter = metrics.meter(
                        MetricRegistry.name(HystrixJobQueue.class, "job.added." + getJobId(mlJob)));
                addMeter.mark();
            }
            final HystrixJob j = (HystrixJob) job;
            Future<String> existing = commandsMapping.get(j.getKey());
            if (existing != null) {
                existing.cancel(true);
            }
            addJobCount();
            commandsMapping.put(j.getKey(), j.queue());
            if(mapSize < commandsMapping.size()) {
                mapSize = commandsMapping.size();
            }
        }
        else {
            super.add(job);
        }
    }

    /**
     * Custom handling for HystrixJobs, call super on other type of jobs
     * @param job
     */
    public void remove(Job job) {
        if(job instanceof HystrixJob) {
            Future<String> existing = commandsMapping.get(job.getKey());
            if (existing != null) {
                commandsMapping.remove(job.getKey());
                existing.cancel(true);
            }
        }
        else {
            super.remove(job);
        }
    }
}
