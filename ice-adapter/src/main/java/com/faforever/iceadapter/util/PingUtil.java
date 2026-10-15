package com.faforever.iceadapter.util;

import com.faforever.iceadapter.IceAdapter;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import java.util.OptionalDouble;
import java.util.concurrent.CompletableFuture;
import lombok.experimental.UtilityClass;

@UtilityClass
public class PingUtil {

    private final LoadingCache<String, CompletableFuture<OptionalDouble>> hostRTTCache = CacheBuilder.newBuilder()
            .build(new CacheLoader<>() {
                @Override
                public CompletableFuture<OptionalDouble> load(String host) {
                    return PingWrapper.getLatency(host, IceAdapter.getPingCount())
                            .thenApply(OptionalDouble::of)
                            .exceptionally(ex -> OptionalDouble.empty());
                }
            });

    public CompletableFuture<OptionalDouble> getLatency(String host) {
        return hostRTTCache.getUnchecked(host);
    }
}
