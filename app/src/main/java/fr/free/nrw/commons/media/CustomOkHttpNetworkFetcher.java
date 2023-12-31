package fr.free.nrw.commons.media;

import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import com.facebook.imagepipeline.common.BytesRange;
import com.facebook.imagepipeline.image.EncodedImage;
import com.facebook.imagepipeline.producers.BaseNetworkFetcher;
import com.facebook.imagepipeline.producers.BaseProducerContextCallbacks;
import com.facebook.imagepipeline.producers.Consumer;
import com.facebook.imagepipeline.producers.FetchState;
import com.facebook.imagepipeline.producers.NetworkFetcher;
import com.facebook.imagepipeline.producers.ProducerContext;
import fr.free.nrw.commons.CommonsApplication;
import fr.free.nrw.commons.kvstore.JsonKvStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import okhttp3.CacheControl;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import timber.log.Timber;

// Custom implementation of Fresco's Network fetcher to skip downloading of images when limited connection mode is enabled
// https://github.com/facebook/fresco/blob/master/imagepipeline-backends/imagepipeline-okhttp3/src/main/java/com/facebook/imagepipeline/backends/okhttp3/OkHttpNetworkFetcher.java
@Singleton
public class CustomOkHttpNetworkFetcher
    extends BaseNetworkFetcher<CustomOkHttpNetworkFetcher.OkHttpNetworkFetchState> {

    private static final String QUEUE_TIME = "queue_time";
    private static final String FETCH_TIME = "fetch_time";
    private static final String TOTAL_TIME = "total_time";
    private static final String IMAGE_SIZE = "image_size";
    private final Call.Factory mCallFactory;
    private final @Nullable
    CacheControl mCacheControl;
    private final Executor mCancellationExecutor;
    private final JsonKvStore defaultKvStore;

    /**
     * @param okHttpClient client to use
     */
    @Inject
    public CustomOkHttpNetworkFetcher(final OkHttpClient okHttpClient,
        @Named("default_preferences") final JsonKvStore defaultKvStore) {
        this(okHttpClient, okHttpClient.dispatcher().executorService(), defaultKvStore);
    }

    /**
     * @param callFactory          custom {@link Call.Factory} for fetching image from the network
     * @param cancellationExecutor executor on which fetching cancellation is performed if
     *                             cancellation is requested from the UI Thread
     */
    public CustomOkHttpNetworkFetcher(final Call.Factory callFactory,
        final Executor cancellationExecutor,
        final JsonKvStore defaultKvStore) {
        this(callFactory, cancellationExecutor, defaultKvStore, true);
    }

    /**
     * @param callFactory          custom {@link Call.Factory} for fetching image from the network
     * @param cancellationExecutor executor on which fetching cancellation is performed if
     *                             cancellation is requested from the UI Thread
     * @param disableOkHttpCache   true if network requests should not be cached by OkHttp
     */
    public CustomOkHttpNetworkFetcher(
        final Call.Factory callFactory, final Executor cancellationExecutor,
        final JsonKvStore defaultKvStore,
        final boolean disableOkHttpCache) {
        this.defaultKvStore = defaultKvStore;
        mCallFactory = callFactory;
        mCancellationExecutor = cancellationExecutor;
        mCacheControl = disableOkHttpCache ? new CacheControl.Builder().noStore().build() : null;
    }

    @Override
    public OkHttpNetworkFetchState createFetchState(
        final Consumer<EncodedImage> consumer, final ProducerContext context) {
        return new OkHttpNetworkFetchState(consumer, context);
    }

    @Override
    public void fetch(
        final OkHttpNetworkFetchState fetchState, final NetworkFetcher.Callback callback) {
        fetchState.submitTime = SystemClock.elapsedRealtime();
        final Uri uri = fetchState.getUri();

        try {
            if (defaultKvStore
                .getBoolean(CommonsApplication.IS_LIMITED_CONNECTION_MODE_ENABLED, false)) {
                Timber.d("Skipping loading of image as limited connection mode is enabled");
                callback.onFailure(
                    new Exception("Failing image request as limited connection mode is enabled"));
                return;
            }
            final Request.Builder requestBuilder = new Request.Builder().url(uri.toString()).get();

            if (mCacheControl != null) {
                requestBuilder.cacheControl(mCacheControl);
            }

            final BytesRange bytesRange = fetchState.getContext().getImageRequest().getBytesRange();
            if (bytesRange != null) {
                requestBuilder.addHeader("Range", bytesRange.toHttpRangeHeaderValue());
            }

            fetchWithRequest(fetchState, callback, requestBuilder.build());
        } catch (final Exception e) {
            // handle error while creating the request
            callback.onFailure(e);
        }
    }

    @Override
    public void onFetchCompletion(final OkHttpNetworkFetchState fetchState, final int byteSize) {
        fetchState.fetchCompleteTime = SystemClock.elapsedRealtime();
    }

    @Override
    public Map<String, String> getExtraMap(final OkHttpNetworkFetchState fetchState,
        final int byteSize) {
        final Map<String, String> extraMap = new HashMap<>(4);
        extraMap.put(QUEUE_TIME, Long.toString(fetchState.responseTime - fetchState.submitTime));
        extraMap
            .put(FETCH_TIME, Long.toString(fetchState.fetchCompleteTime - fetchState.responseTime));
        extraMap
            .put(TOTAL_TIME, Long.toString(fetchState.fetchCompleteTime - fetchState.submitTime));
        extraMap.put(IMAGE_SIZE, Integer.toString(byteSize));
        return extraMap;
    }

    protected void fetchWithRequest(
        final OkHttpNetworkFetchState fetchState,
        final NetworkFetcher.Callback callback,
        final Request request) {
        final Call call = mCallFactory.newCall(request);

        fetchState
            .getContext()
            .addCallbacks(
                new BaseProducerContextCallbacks() {
                    @Override
                    public void onCancellationRequested() {
                        onFetchCancellationRequested(call);
                    }
                });

        call.enqueue(
            new okhttp3.Callback() {
                @Override
                public void onResponse(final Call call, final Response response) {
                    onFetchResponse(fetchState, call, response, callback);
                }

                @Override
                public void onFailure(final Call call, final IOException e) {
                    handleException(call, e, callback);
                }
            });
    }

    private void onFetchCancellationRequested(final Call call) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            call.cancel();
        } else {
            mCancellationExecutor.execute(call::cancel);
        }
    }

    private void onFetchResponse(final OkHttpNetworkFetchState fetchState, final Call call,
        final Response response,
        final NetworkFetcher.Callback callback) {
        fetchState.responseTime = SystemClock.elapsedRealtime();
        try (final ResponseBody body = response.body()) {
            if (!response.isSuccessful()) {
                handleException(
                    call, new IOException("Unexpected HTTP code " + response),
                    callback);
                return;
            }

            final BytesRange responseRange =
                BytesRange.fromContentRangeHeader(response.header("Content-Range"));
            if (responseRange != null
                && !(responseRange.from == 0
                && responseRange.to == BytesRange.TO_END_OF_CONTENT)) {
                // Only treat as a partial image if the range is not all of the content
                fetchState.setResponseBytesRange(responseRange);
                fetchState.setOnNewResultStatusFlags(Consumer.IS_PARTIAL_RESULT);
            }

            long contentLength = body.contentLength();
            if (contentLength < 0) {
                contentLength = 0;
            }
            callback.onResponse(body.byteStream(), (int) contentLength);
        } catch (final Exception e) {
            handleException(call, e, callback);
        }
    }

    /**
     * Handles exceptions.
     *
     * <p>OkHttp notifies callers of cancellations via an IOException. If IOException is caught
     * after request cancellation, then the exception is interpreted as successful cancellation and
     * onCancellation is called. Otherwise onFailure is called.
     */
    private void handleException(final Call call, final Exception e, final Callback callback) {
        if (call.isCanceled()) {
            callback.onCancellation();
        } else {
            callback.onFailure(e);
        }
    }

    public static class OkHttpNetworkFetchState extends FetchState {

        public long submitTime;
        public long responseTime;
        public long fetchCompleteTime;

        public OkHttpNetworkFetchState(
            final Consumer<EncodedImage> consumer, final ProducerContext producerContext) {
            super(consumer, producerContext);
        }
    }
}
