package reactivestreams.commons;

import java.util.Objects;
import java.util.function.Supplier;

import org.reactivestreams.*;

import reactivestreams.commons.internal.subscriptions.ScalarSubscription;

public final class PublisherJust<T> implements Supplier<T>, Publisher<T> {

    final T value;
    
    public PublisherJust(T value) {
        this.value = Objects.requireNonNull(value, "value");
    }
    
    @Override
    public T get() {
        return value;
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        s.onSubscribe(new ScalarSubscription<>(s, value));
    }
    
}
