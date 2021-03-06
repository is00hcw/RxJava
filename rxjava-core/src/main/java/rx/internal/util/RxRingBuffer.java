/**
 * Copyright 2014 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.internal.util;

import java.util.Queue;

import rx.Observer;
import rx.Subscription;
import rx.exceptions.MissingBackpressureException;
import rx.internal.operators.NotificationLite;
import rx.internal.util.unsafe.SpmcArrayQueue;
import rx.internal.util.unsafe.SpscArrayQueue;
import rx.internal.util.unsafe.UnsafeAccess;

public class RxRingBuffer implements Subscription {

    public static RxRingBuffer getSpscInstance() {
        if (UnsafeAccess.isUnsafeAvailable()) {
            return new RxRingBuffer(SPSC_POOL, SIZE);
        } else {
            return new RxRingBuffer();
        }
    }

    public static RxRingBuffer getSpmcInstance() {
        if (UnsafeAccess.isUnsafeAvailable()) {
            return new RxRingBuffer(SPMC_POOL, SIZE);
        } else {
            return new RxRingBuffer();
        }
    }

    /**
     * Queue implementation testing that led to current choices of data structures:
     * 
     * With synchronized LinkedList
     * <pre> {@code
     * Benchmark                                        Mode   Samples        Score  Score error    Units
     * r.i.RxRingBufferPerf.ringBufferAddRemove1       thrpt         5 19118392.046  1002814.238    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1000    thrpt         5    17891.641      252.747    ops/s
     * 
     * With MpscPaddedQueue (single consumer, so failing 1 unit test)
     * 
     * Benchmark                                        Mode   Samples        Score  Score error    Units
     * r.i.RxRingBufferPerf.ringBufferAddRemove1       thrpt         5 22164483.238  3035027.348    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1000    thrpt         5    23154.303      602.548    ops/s
     * 
     * 
     * With ConcurrentLinkedQueue (tracking count separately)
     * 
     * Benchmark                                        Mode   Samples        Score  Score error    Units
     * r.i.RxRingBufferPerf.ringBufferAddRemove1       thrpt         5 17353906.092   378756.411    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1000    thrpt         5    19224.411     1010.610    ops/s
     * 
     * With ConcurrentLinkedQueue (using queue.size() method for count)
     * 
     * Benchmark                                        Mode   Samples        Score  Score error    Units
     * r.i.RxRingBufferPerf.ringBufferAddRemove1       thrpt         5 23951121.098  1982380.330    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1000    thrpt         5     1142.351       33.592    ops/s
     * 
     * With SpscArrayQueue (single consumer, so failing 1 unit test)
     *  - requires access to Unsafe
     * 
     * Benchmark                                        Mode   Samples        Score  Score error    Units
     * r.i.RxRingBufferPerf.createUseAndDestroy1       thrpt         5  1922996.035    49183.766    ops/s
     * r.i.RxRingBufferPerf.createUseAndDestroy1000    thrpt         5    70890.186     1382.550    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1       thrpt         5 80637811.605  3509706.954    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1000    thrpt         5    71822.453     4127.660    ops/s
     * 
     * 
     * With SpscArrayQueue and Object Pool (object pool improves createUseAndDestroy1 by 10x)
     * 
     * Benchmark                                        Mode   Samples        Score  Score error    Units
     * r.i.RxRingBufferPerf.createUseAndDestroy1       thrpt         5 25220069.264  1329078.785    ops/s
     * r.i.RxRingBufferPerf.createUseAndDestroy1000    thrpt         5    72313.457     3535.447    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1       thrpt         5 81863840.884  2191416.069    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1000    thrpt         5    73140.822     1528.764    ops/s
     * 
     * With SpmcArrayQueue
     *  - requires access to Unsafe
     *  
     * Benchmark                                        Mode   Samples        Score  Score error    Units
     * r.i.RxRingBufferPerf.createUseAndDestroy1       thrpt         5  1835494.523    63874.461    ops/s
     * r.i.RxRingBufferPerf.createUseAndDestroy1000    thrpt         5    45545.599     1882.146    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1       thrpt         5 38126258.816   474874.236    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1000    thrpt         5    42507.743      240.530    ops/s
     * 
     * With SpmcArrayQueue and ObjectPool (object pool improves createUseAndDestroy1 by 10x)
     * 
     * Benchmark                                        Mode   Samples        Score  Score error    Units
     * r.i.RxRingBufferPerf.createUseAndDestroy1       thrpt         5 18489343.061  1011872.825    ops/s
     * r.i.RxRingBufferPerf.createUseAndDestroy1000    thrpt         5    46416.434     1439.144    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove        thrpt         5 38280945.847  1071801.279    ops/s
     * r.i.RxRingBufferPerf.ringBufferAddRemove1000    thrpt         5    42337.663     1052.231    ops/s
     * 
     * --------------
     * 
     * When UnsafeAccess.isUnsafeAvailable() == true we can use the Spmc/SpscArrayQueue implementations and get these numbers:
     * 
     * Benchmark                                            Mode   Samples        Score  Score error    Units
     * r.i.RxRingBufferPerf.spmcCreateUseAndDestroy1       thrpt         5 17813072.116   672207.872    ops/s
     * r.i.RxRingBufferPerf.spmcCreateUseAndDestroy1000    thrpt         5    46794.691     1146.195    ops/s
     * r.i.RxRingBufferPerf.spmcRingBufferAddRemove1       thrpt         5 32117630.315   749011.552    ops/s
     * r.i.RxRingBufferPerf.spmcRingBufferAddRemove1000    thrpt         5    47257.476     1081.623    ops/s
     * r.i.RxRingBufferPerf.spscCreateUseAndDestroy1       thrpt         5 24729994.601   353101.940    ops/s
     * r.i.RxRingBufferPerf.spscCreateUseAndDestroy1000    thrpt         5    73101.460     2406.377    ops/s
     * r.i.RxRingBufferPerf.spscRingBufferAddRemove1       thrpt         5 83548821.062   752738.756    ops/s
     * r.i.RxRingBufferPerf.spscRingBufferAddRemove1000    thrpt         5    70549.816     1377.227    ops/s
     * 
     * } </pre>
     */

    private static final NotificationLite<Object> on = NotificationLite.instance();

    private Queue<Object> queue;

    private final int size;
    private final ObjectPool<Queue<Object>> pool;

    private volatile Object terminalState;

    public static final int SIZE = 1024;

    private static ObjectPool<Queue<Object>> SPSC_POOL = new ObjectPool<Queue<Object>>() {

        @Override
        protected SpscArrayQueue<Object> createObject() {
            return new SpscArrayQueue(SIZE);
        }

    };

    private static ObjectPool<Queue<Object>> SPMC_POOL = new ObjectPool<Queue<Object>>() {

        @Override
        protected SpmcArrayQueue<Object> createObject() {
            return new SpmcArrayQueue(SIZE);
        }

    };

    private RxRingBuffer(Queue queue, int size) {
        this.queue = queue;
        this.pool = null;
        this.size = size;
    }

    private RxRingBuffer(ObjectPool<Queue<Object>> pool, int size) {
        this.pool = pool;
        this.queue = pool.borrowObject();
        this.size = size;
    }

    public void release() {
        if (pool != null) {
            Queue q = queue;
            q.clear();
            queue = null;
            pool.returnObject(q);
        }
    }

    @Override
    public void unsubscribe() {
        release();
    }

    /* for unit tests */RxRingBuffer() {
        this(new SynchronizedQueue<Queue>(SIZE), SIZE);
    }

    /**
     * 
     * @param o
     * @throws MissingBackpressureException
     *             if more onNext are sent than have been requested
     */
    public void onNext(Object o) throws MissingBackpressureException {
        if (queue == null) {
            throw new IllegalStateException("This instance has been unsubscribed and the queue is no longer usable.");
        }
        if (!queue.offer(on.next(o))) {
            throw new MissingBackpressureException();
        }
    }

    public void onCompleted() {
        // we ignore terminal events if we already have one
        if (terminalState == null) {
            terminalState = on.completed();
        }
    }

    public void onError(Throwable t) {
        // we ignore terminal events if we already have one
        if (terminalState == null) {
            terminalState = on.error(t);
        }
    }

    public int available() {
        return size - count();
    }

    public int capacity() {
        return size;
    }

    public int count() {
        if (queue == null) {
            return 0;
        }
        return queue.size();
    }

    public Object poll() {
        if (queue == null) {
            // we are unsubscribed and have released the undelrying queue
            return null;
        }
        Object o;
        o = queue.poll();
        if (o == null && terminalState != null) {
            o = terminalState;
            // once emitted we clear so a poll loop will finish
            terminalState = null;
        }
        return o;
    }

    public boolean isCompleted(Object o) {
        return on.isCompleted(o);
    }

    public boolean isError(Object o) {
        return on.isError(o);
    }

    public boolean accept(Object o, Observer child) {
        return on.accept(child, o);
    }

    public Throwable asError(Object o) {
        return on.getError(o);
    }

    @Override
    public boolean isUnsubscribed() {
        return queue == null;
    }

}
