/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package co.cask.cdap.metrics.collect;

import co.cask.cdap.api.metrics.MetricValue;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.metrics.MetricsCollectionService;
import co.cask.cdap.common.metrics.MetricsCollector;
import co.cask.cdap.metrics.iterator.MetricsCollectorIterator;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.AbstractScheduledService;
import org.apache.twill.common.Threads;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Base class for {@link MetricsCollectionService} which collect metrics through a set of cached
 * {@link AggregatedMetricsEmitter}.
 */
public abstract class AggregatedMetricsCollectionService extends AbstractScheduledService
                                                         implements MetricsCollectionService {

  private static final Logger LOG = LoggerFactory.getLogger(AggregatedMetricsCollectionService.class);
  private static final long CACHE_EXPIRE_MINUTES = 1;

  private final LoadingCache<Map<String, String>, MetricsCollector> collectors;
  private final LoadingCache<Map<String, String>, AggregatedMetricsEmitter> emitters;

  private ScheduledExecutorService executorService;

  public AggregatedMetricsCollectionService() {
    this.collectors = CacheBuilder.newBuilder()
      .expireAfterAccess(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
      .build(createCollectorLoader());

    this.emitters = CacheBuilder.newBuilder()
      // NOTE : we don't need to have removalListener to  emit metrics, as we have expireAfterAccess set for a minute,
      // emitters.get() is used to increment/gauge and that would reset the access time,
      // and since runOneIteration() emits all the metrics for the scheduled duration (every 1 second)
      // there wont be any loss of emitter entries.
      .expireAfterAccess(CACHE_EXPIRE_MINUTES, TimeUnit.MINUTES)
      .build(new CacheLoader<Map<String, String>, AggregatedMetricsEmitter>() {
        @Override
        public AggregatedMetricsEmitter load(Map<String, String> tags) throws Exception {
          return new AggregatedMetricsEmitter(tags);
        }
      });
  }

  /**
   * Publishes the given collection of {@link MetricValue}. When this method returns, the
   * given {@link Iterator} will no longer be valid. This method should process the input
   * iterator and returns quickly. Any long operations should be run in a separated thread.
   * This method is guaranteed not to get concurrent calls.
   *
   * @param metrics collection of {@link co.cask.cdap.api.metrics.MetricValue} to publish.
   * @throws Exception if there is error raised during publish.
   */
  protected abstract void publish(Iterator<MetricValue> metrics) throws Exception;

  /**
   * @return true if we want to publish metrics about
   *              the received metrics in {@link #publish(java.util.Iterator)}.
   */
  protected boolean isPublishMetaMetrics() {
    return true;
  }

  @Override
  protected final void runOneIteration() throws Exception {
    final long timestamp = TimeUnit.SECONDS.convert(System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    LOG.trace("Start log collection for timestamp {}", timestamp);

    final MetricsCollectorIterator metricsItor = new MetricsCollectorIterator(getMetrics(timestamp));
    publishMetrics(timestamp, metricsItor);
  }

  private void publishMetrics(long timestamp, MetricsCollectorIterator metricsItor) {
    try {
      publish(metricsItor);
    } catch (Throwable t) {
      LOG.error("Failed in publishing metrics for timestamp {}.", timestamp, t);
    }

    if (isPublishMetaMetrics()) {
      try {
        publish(metricsItor.getMetaMetrics());
      } catch (Throwable t) {
        LOG.error("Failed in publishing meta metrics for timestamp {}.", timestamp, t);
      }
    }

    // Consume the whole iterator if it is not yet consumed inside publish. This is to make sure metrics are reset.
    while (metricsItor.hasNext()) {
      metricsItor.next();
    }
    LOG.trace("Completed log collection for timestamp {}", timestamp);
  }

  @Override
  protected ScheduledExecutorService executor() {
    executorService = Executors.newSingleThreadScheduledExecutor(
      Threads.createDaemonThreadFactory("metrics-collection"));
    return executorService;
  }

  @Override
  protected Scheduler scheduler() {
    return Scheduler.newFixedRateSchedule(Constants.MetricsCollector.DEFAULT_FREQUENCY_SECONDS,
                                          Constants.MetricsCollector.DEFAULT_FREQUENCY_SECONDS,
                                          TimeUnit.SECONDS);
  }

  @Override
  public final MetricsCollector getCollector(final Map<String, String> tags) {
    return collectors.getUnchecked(tags);
  }

  @Override
  protected void shutDown() throws Exception {
    // Flush the metrics when shutting down.
    try {
      runOneIteration();
    } finally {
      if (executorService != null) {
        executorService.shutdownNow();
      }
    }
  }

  private Iterator<MetricValue> getMetrics(final long timestamp) {
    // NOTE : emitters.asMap does not reset the access time in cache,
    // so it's the preferred way to access the cache entries. as we access and emit metrics every second.
    final Iterator<AggregatedMetricsEmitter> iterator = emitters.asMap().values().iterator();
    return new AbstractIterator<MetricValue>() {
      @Override
      protected MetricValue computeNext() {
        while (iterator.hasNext()) {
          AggregatedMetricsEmitter entry = iterator.next();
          MetricValue metricValue = entry.emit(timestamp);
          if (metricValue.getMetrics().isEmpty()) {
            // skip if there are no metrics to send ( we skip counters with value '0')
            continue;
          }
          LOG.trace("Emit metric {}", metricValue);
          return metricValue;
        }
        return endOfData();
      }
    };
  }

  private CacheLoader<Map<String, String>, MetricsCollector> createCollectorLoader() {
    return new CacheLoader<Map<String, String>, MetricsCollector>() {
      @Override
      public MetricsCollector load(final Map<String, String> collectorKey) throws Exception {
        return new MetricsCollectorImpl(collectorKey);
      }
    };
  }

  private final class MetricsCollectorImpl implements MetricsCollector {

    private final Map<String, String> tags;

    private MetricsCollectorImpl(final Map<String, String> tags) {
      this.tags = tags;
    }

    @Override
    public void increment(String metricName, long value) {
      emitters.getUnchecked(tags).increment(metricName, value);
    }

    @Override
    public void gauge(String metricName, long value) {
      emitters.getUnchecked(tags).gauge(metricName, value);
    }

    @Override
    public MetricsCollector childCollector(String tagName, String tagValue) {
      ImmutableMap<String, String> allTags = ImmutableMap.<String, String>builder()
        .putAll(tags).put(tagName, tagValue).build();
      return collectors.getUnchecked(allTags);
    }

    @Override
    public MetricsCollector childCollector(Map<String, String> tags) {
      if (tags.isEmpty()) {
        return this;
      }
      // todo: may be warn when duplicate tag is provided? for now ok
      Map<String, String> allTags = Maps.newHashMap();
      allTags.putAll(this.tags);
      allTags.putAll(tags);
      return collectors.getUnchecked(allTags);
    }
  }
}
