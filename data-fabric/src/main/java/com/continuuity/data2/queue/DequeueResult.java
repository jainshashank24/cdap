/*
 * Copyright 2012-2013 Continuuity,Inc. All Rights Reserved.
 */
package com.continuuity.data2.queue;

import com.continuuity.common.queue.QueueName;

/**
 *
 */
public interface DequeueResult {

  /**
   * Returns the queue where the dequeue happens.
   */
  QueueName getQueueName();

  /**
   * Returns consumer configuration for the consumer who dequeue.
   */
  ConsumerConfig getConsumerConfig();

  /**
   * Returns {@code true} if there is no data in the queue.
   */
  boolean isEmpty();


  /**
   * Returns entries being dequeued. If the dequeue result is empty, this method returns an empty collection.
   */
  Iterable<byte[]> getData();
}
