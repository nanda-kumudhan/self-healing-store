package dev.healstore.common.io;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** The HTTP operation completes only after its bounded body, so its deadline covers stalled bodies too. */
final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private int received;
    private boolean failed;
    @Override
    public CompletionStage<byte[]> getBody() {
        return delegate.getBody();
    }
    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        delegate.onSubscribe(subscription);
    }
    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (failed)
            return;
        for (var buffer : buffers) {
            if (buffer.remaining() > 2 * 1024 * 1024 - received) {
                failed = true;
                subscription.cancel();
                delegate.onError(new IOException("Response exceeded size limit"));
                return;
            }
            received += buffer.remaining();
        }
        delegate.onNext(buffers);
    }
    @Override
    public void onError(Throwable error) {
        delegate.onError(error);
    }
    @Override
    public void onComplete() {
        delegate.onComplete();
    }
}
