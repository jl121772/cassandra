/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.streaming;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.RateLimiter;
import org.cliffc.high_scale_lib.NonBlockingHashMap;

import org.apache.cassandra.config.DatabaseDescriptor;

/**
 * StreamManager manages currently running {@link StreamResultFuture}s and provides status of all operation invoked.
 *
 * All stream operation should be created through this class to track streaming status and progress.
 */
public class StreamManager implements StreamManagerMBean, FutureCallback<StreamState>
{
    public static final StreamManager instance = new StreamManager();

    private static final Map<InetAddress, RateLimiter> rateLimiters = new NonBlockingHashMap<>();

    /**
     * Gets streaming rate limiter associated with given address.
     * When stream_throughput_outbound_megabits_per_sec is 0, this returns rate limiter
     * with the rate of Double.MAX_VALUE bytes per second.
     * Rate unit is bytes per sec.
     *
     * @param address address to apply RateLimiter
     * @return RateLimiter with rate limit set
     */
    public static RateLimiter getRateLimiter(InetAddress address)
    {
        RateLimiter limiter = rateLimiters.get(address);
        if (limiter == null)
        {
            limiter = RateLimiter.create(Double.MAX_VALUE);
            rateLimiters.put(address, limiter);
        }
        double currentThroughput = DatabaseDescriptor.getStreamThroughputOutboundMegabitsPerSec() * 1024 * 1024 / 8 / 1000;
        // if throughput is set to 0, throttling is disabled
        if (currentThroughput == 0)
            currentThroughput = Double.MAX_VALUE;
        if (limiter.getRate() != currentThroughput)
            limiter.setRate(currentThroughput);
        return limiter;
    }

    /** Currently running stream plans. Removed after completion/failure. */
    private final Map<UUID, StreamResultFuture> currentStreams = new NonBlockingHashMap<>();

    public List<UUID> getCurrentStreamPlans()
    {
        return Lists.newArrayList(currentStreams.keySet());
    }

    public Set<StreamState> getCurrentStatus()
    {
        return Sets.newHashSet(Iterables.transform(currentStreams.values(), new Function<StreamResultFuture, StreamState>()
        {
            public StreamState apply(StreamResultFuture input)
            {
                return input.getCurrentState();
            }
        }));
    }

    public void onSuccess(StreamState finalState)
    {
        currentStreams.remove(finalState.planId);
    }

    public void onFailure(Throwable t)
    {
        if (t instanceof StreamException)
        {
            currentStreams.remove(((StreamException) t).finalState.planId);
        }
    }

    public void register(StreamResultFuture result)
    {
        Futures.addCallback(result, this);
        currentStreams.put(result.planId, result);
    }
}
