package rsc.parallel;

import java.util.Queue;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import org.reactivestreams.*;

import rsc.publisher.Px;
import rsc.util.*;

/**
 * Merges the individual 'rails' of the source ParallelPublisher, unordered,
 * into a single regular Publisher sequence (exposed as Px).
 *
 * @param <T> the value type
 */
public final class ParallelUnorderedJoin<T> extends Px<T> {
    final ParallelPublisher<? extends T> source;
    final int prefetch;
    final Supplier<Queue<T>> queueSupplier;
    
    public ParallelUnorderedJoin(ParallelPublisher<? extends T> source, int prefetch, Supplier<Queue<T>> queueSupplier) {
        this.source = source;
        this.prefetch = prefetch;
        this.queueSupplier = queueSupplier;
    }
    
    @Override
    public void subscribe(Subscriber<? super T> s) {
        JoinSubscription<T> parent = new JoinSubscription<>(s, source.parallelism(), prefetch, queueSupplier);
        s.onSubscribe(parent);
        source.subscribe(parent.subscribers);
    }
    
    static final class JoinSubscription<T> implements Subscription {
        final Subscriber<? super T> actual;
        
        final JoinInnerSubscriber<T>[] subscribers;
        
        final Supplier<Queue<T>> queueSupplier;
        
        volatile Throwable error;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<JoinSubscription, Throwable> ERROR =
                AtomicReferenceFieldUpdater.newUpdater(JoinSubscription.class, Throwable.class, "error");
        
        volatile int wip;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<JoinSubscription> WIP =
                AtomicIntegerFieldUpdater.newUpdater(JoinSubscription.class, "wip");
        
        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<JoinSubscription> REQUESTED =
                AtomicLongFieldUpdater.newUpdater(JoinSubscription.class, "requested");
        
        volatile boolean cancelled;

        volatile int done;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<JoinSubscription> DONE =
                AtomicIntegerFieldUpdater.newUpdater(JoinSubscription.class, "done");

        public JoinSubscription(Subscriber<? super T> actual, int n, int prefetch, Supplier<Queue<T>> queueSupplier) {
            this.actual = actual;
            this.queueSupplier = queueSupplier;
            @SuppressWarnings("unchecked")
            JoinInnerSubscriber<T>[] a = new JoinInnerSubscriber[n];
            
            for (int i = 0; i < n; i++) {
                a[i] = new JoinInnerSubscriber<>(this, prefetch);
            }
            
            this.subscribers = a;
            DONE.lazySet(this, n);
        }
        
        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.getAndAddCap(REQUESTED, this, n);
                drain();
            }
        }
        
        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                
                cancelAll();
                
                if (WIP.getAndIncrement(this) == 0) {
                    cleanup();
                }
            }
        }
        
        void cancelAll() {
            for (JoinInnerSubscriber<T> s : subscribers) {
                s.cancel();
            }
        }
        
        void cleanup() {
            for (JoinInnerSubscriber<T> s : subscribers) {
                s.queue = null; 
            }
        }
        
        void onNext(JoinInnerSubscriber<T> inner, T value) {
            if (wip == 0 && WIP.compareAndSet(this, 0, 1)) {
                if (requested != 0) {
                    actual.onNext(value);
                    if (requested != Long.MAX_VALUE) {
                        REQUESTED.decrementAndGet(this);
                    }
                    inner.request(1);
                } else {
                    Queue<T> q = inner.getQueue(queueSupplier);

                    // FIXME overflow handling
                    q.offer(value);
                }
                if (WIP.decrementAndGet(this) == 0) {
                    return;
                }
            } else {
                Queue<T> q = inner.getQueue(queueSupplier);
                
                // FIXME overflow handling
                q.offer(value);

                if (WIP.getAndIncrement(this) != 0) {
                    return;
                }
            }
            
            drainLoop();
        }
        
        void onError(Throwable e) {
            if (ExceptionHelper.addThrowable(ERROR, this, e)) {
                cancelAll();
                drain();
            } else {
                UnsignalledExceptions.onErrorDropped(e);
            }
        }
        
        void onComplete() {
            DONE.decrementAndGet(this);
            drain();
        }
        
        void drain() {
            if (WIP.getAndIncrement(this) != 0) {
                return;
            }
            
            drainLoop();
        }
        
        void drainLoop() {
            int missed = 1;
            
            JoinInnerSubscriber<T>[] s = this.subscribers;
            int n = s.length;
            Subscriber<? super T> a = this.actual;
            
            for (;;) {
                
                long r = requested;
                long e = 0;
                
                middle:
                while (e != r) {
                    if (cancelled) {
                        cleanup();
                        return;
                    }
                    
                    Throwable ex = error;
                    if (ex != null) {
                        ex = ExceptionHelper.terminate(ERROR, this);
                        cleanup();
                        a.onError(ex);
                        return;
                    }
                    
                    boolean d = done == 0;
                    
                    boolean empty = true;
                    
                    for (int i = 0; i < n; i++) {
                        JoinInnerSubscriber<T> inner = s[i];
                        
                        Queue<T> q = inner.queue;
                        if (q != null) {
                            T v = q.poll();
                            
                            if (v != null) {
                                empty = false;
                                a.onNext(v);
                                inner.requestOne();
                                if (++e == r) {
                                    break middle;
                                }
                            }
                        }
                    }
                    
                    if (d && empty) {
                        a.onComplete();
                        return;
                    }
                    
                    if (empty) {
                        break;
                    }
                }
                
                if (e == r) {
                    if (cancelled) {
                        cleanup();
                        return;
                    }
                    
                    Throwable ex = error;
                    if (ex != null) {
                        ex = ExceptionHelper.terminate(ERROR, this);
                        cleanup();
                        a.onError(ex);
                        return;
                    }
                    
                    boolean d = done == 0;
                    
                    boolean empty = true;
                    
                    for (int i = 0; i < n; i++) {
                        JoinInnerSubscriber<T> inner = s[i];
                        
                        Queue<T> q = inner.queue;
                        if (q != null && !q.isEmpty()) {
                            empty = false;
                            break;
                        }
                    }
                    
                    if (d && empty) {
                        a.onComplete();
                        return;
                    }
                }
                
                if (e != 0 && r != Long.MAX_VALUE) {
                    REQUESTED.addAndGet(this, -e);
                }
                
                int w = wip;
                if (w == missed) {
                    missed = WIP.addAndGet(this, -missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }
    }
    
    static final class JoinInnerSubscriber<T> implements Subscriber<T> {
        
        final JoinSubscription<T> parent;
        
        final int prefetch;
        
        final int limit;
        
        long produced;
        
        volatile Subscription s;
        @SuppressWarnings("rawtypes")
        static final AtomicReferenceFieldUpdater<JoinInnerSubscriber, Subscription> S =
                AtomicReferenceFieldUpdater.newUpdater(JoinInnerSubscriber.class, Subscription.class, "s");
        
        volatile Queue<T> queue;
        
        volatile boolean done;
        
        public JoinInnerSubscriber(JoinSubscription<T> parent, int prefetch) {
            this.parent = parent;
            this.prefetch = prefetch ;
            this.limit = prefetch - (prefetch >> 2);
        }
        
        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.setOnce(S, this, s)) {
                s.request(prefetch);
            }
        }
        
        @Override
        public void onNext(T t) {
            parent.onNext(this, t);
        }
        
        @Override
        public void onError(Throwable t) {
            parent.onError(t);
        }
        
        @Override
        public void onComplete() {
            parent.onComplete();
        }
        
        public void requestOne() {
            long p = produced + 1;
            if (p == limit) {
                produced = 0;
                s.request(p);
            } else {
                produced = p;
            }
        }

        public void request(long n) {
            long p = produced + n;
            if (p >= limit) {
                produced = 0;
                s.request(p);
            } else {
                produced = p;
            }
        }

        public void cancel() {
            SubscriptionHelper.terminate(S, this);
        }
        
        Queue<T> getQueue(Supplier<Queue<T>> queueSupplier) {
            Queue<T> q = queue;
            if (q == null) {
                q = queueSupplier.get();
                this.queue = q;
            }
            return q;
        }
    }
}
