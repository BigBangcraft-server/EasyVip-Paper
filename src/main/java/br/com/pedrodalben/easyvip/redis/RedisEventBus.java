package br.com.pedrodalben.easyvip.redis;

import br.com.pedrodalben.easyvip.api.DomainEvent;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Optional Redis Pub/Sub transport. SQL-backed operations never depend on it. */
public final class RedisEventBus implements AutoCloseable {
    private final RedisConfig config;
    private final RedisEventCodec codec;
    private final RedisMetrics metrics;
    private final JedisPool pool;
    private final ExecutorService ioExecutor;
    private final ExecutorService subscriptionExecutor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final CompletableFuture<Void> subscriptionReady = new CompletableFuture<>();
    private volatile JedisPubSub subscription;

    public RedisEventBus(RedisConfig config) {
        this(config, new RedisEventCodec(), new RedisMetrics());
    }

    RedisEventBus(RedisConfig config, RedisEventCodec codec, RedisMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(Math.max(4, config.ioThreads() + 2));
        poolConfig.setMaxIdle(Math.max(2, config.ioThreads()));
        poolConfig.setMinIdle(0);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(Duration.ofMillis(config.timeoutMillis()));
        poolConfig.setTestWhileIdle(true);
        poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
        this.pool = new JedisPool(poolConfig, URI.create(config.uri()), config.timeoutMillis());
        int queueCapacity = (int) Math.min(4096L, Math.max(32L, config.ioThreads() * 64L));
        this.ioExecutor = new ThreadPoolExecutor(config.ioThreads(), config.ioThreads(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
                daemonFactory("EasyVip-Redis"), new ThreadPoolExecutor.AbortPolicy());
        this.subscriptionExecutor = Executors.newSingleThreadExecutor(daemonFactory("EasyVip-Redis-Subscription"));
    }

    /** Starts a reconnecting subscription on a daemon thread; never blocks the Paper thread. */
    public boolean start(Consumer<DomainEvent> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        if (!running.compareAndSet(false, true)) return false;
        subscriptionExecutor.submit(() -> subscribeLoop(consumer));
        return true;
    }

    public CompletionStage<Long> publish(DomainEvent event) {
        String payload = codec.encode(event);
        return submitAsync(() -> publishWithRetry(payload));
    }

    public CompletionStage<String> ping() {
        return execute(Jedis::ping);
    }

    public CompletionStage<Void> subscriptionReady() {
        return subscriptionReady;
    }

    public <T> CompletionStage<T> execute(Function<Jedis, T> operation) {
        Objects.requireNonNull(operation, "operation");
        return submitAsync(() -> {
            try (Jedis jedis = pool.getResource()) {
                return operation.apply(jedis);
            } catch (RuntimeException exception) {
                metrics.commandFailed();
                throw exception;
            }
        });
    }

    private <T> CompletionStage<T> submitAsync(Supplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            ioExecutor.execute(() -> {
                try {
                    future.complete(operation.get());
                } catch (Throwable exception) {
                    future.completeExceptionally(exception);
                }
            });
        } catch (RejectedExecutionException rejected) {
            metrics.commandFailed();
            future.completeExceptionally(new IllegalStateException("Redis executor is saturated", rejected));
        }
        return future;
    }

    private long publishWithRetry(String payload) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Jedis jedis = pool.getResource()) {
                long delivered = jedis.publish(config.channel(), payload);
                metrics.published();
                return delivered;
            } catch (RuntimeException exception) {
                metrics.publishFailed();
                last = exception;
                if (attempt < 2) {
                    try {
                        Thread.sleep(50L << attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Redis publish interrupted", interrupted);
                    }
                }
            }
        }
        throw new IllegalStateException("Redis publish failed after retries", last);
    }

    private void subscribeLoop(Consumer<DomainEvent> consumer) {
        while (running.get()) {
            try (Jedis jedis = pool.getResource()) {
                JedisPubSub listener = new JedisPubSub() {
                    @Override
                    public void onSubscribe(String channel, int subscribedChannels) {
                        subscriptionReady.complete(null);
                    }

                    @Override
                    public void onMessage(String channel, String message) {
                        metrics.received();
                        try {
                            consumer.accept(codec.decode(message));
                        } catch (RuntimeException exception) {
                            metrics.invalidEvent();
                        }
                    }
                };
                subscription = listener;
                jedis.subscribe(listener, config.channel());
            } catch (RuntimeException exception) {
                metrics.commandFailed();
                if (running.get()) {
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } finally {
                subscription = null;
            }
        }
    }

    public boolean isRunning() { return running.get(); }
    public RedisMetrics metrics() { return metrics; }
    public RedisConfig config() { return config; }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            ioExecutor.shutdownNow();
            subscriptionExecutor.shutdownNow();
            pool.close();
            return;
        }
        JedisPubSub listener = subscription;
        if (listener != null) {
            try { listener.unsubscribe(); } catch (RuntimeException ignored) { }
        }
        ioExecutor.shutdownNow();
        subscriptionExecutor.shutdownNow();
        try { ioExecutor.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        pool.close();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        };
    }
}
