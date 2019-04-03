package top.zhuyuncheng.distributedlock.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Objects;
import java.util.Optional;

@Slf4j
public class RedisLock implements DistributedLock {

    private RedisTemplate redisTemplate;

    private static final int DEFAULT_ACQUIRY_RESOLUTION_MILLIS = 100;

    private volatile boolean locked = false;

    /**
     * Lock key
     */
    private String lockKey;

    /**
     * 锁超时时间，防止线程在入锁以后，无限的执行等待
     */
    private int expireMSECs = 60 * 1000;

    /**
     * 锁等待时间，防止线程饥饿
     */
    private int timeoutMSECs = 10 * 1000;

    public RedisLock(RedisTemplate redisTemplate, String lockKey) {
        this.redisTemplate = redisTemplate;
        this.lockKey = lockKey + "_lock";
    }

    public RedisLock(RedisTemplate redisTemplate, String lockKey, int expireMSECs) {
        this(redisTemplate, lockKey);
        this.expireMSECs = expireMSECs;
    }

    public RedisLock(RedisTemplate redisTemplate, String lockKey, int expireMSECs, int timeoutMSECs) {
        this(redisTemplate, lockKey, expireMSECs);
        this.timeoutMSECs = timeoutMSECs;
    }

    /**
     * @return lockKey
     */
    public String getLockKey() {
        return lockKey;
    }

    private String get(String key) {
        String value = null;
        try {
            value = (String) redisTemplate.execute((RedisCallback) conn -> {
                StringRedisSerializer serializer = new StringRedisSerializer();
                byte[] data = conn.get(Objects.requireNonNull(serializer.serialize(key)));
                conn.close();
                return Optional.ofNullable(data).map(serializer::deserialize).orElse(null);
            });
        } catch (Exception e) {
            log.error("get redis error, key : {}, error: {}", key, e.getMessage());
        }
        return value;
    }

    private boolean setNX(String key, String value) {
        Boolean result = null;
        try {
            result = (Boolean) redisTemplate.execute((RedisCallback) conn -> {
                StringRedisSerializer serializer = new StringRedisSerializer();
                Boolean success = conn.setNX(Objects.requireNonNull(serializer.serialize(key)), Objects.requireNonNull(serializer.serialize(value)));
                conn.close();
                return success;
            });
        } catch (Exception e) {
            log.error("setNX redis error, key : {}, error: {}", key, e.getMessage());
        }
        return result != null && result;
    }

    private String getSet(String key, String value) {
        String oldValue = null;
        try {
            oldValue = (String) redisTemplate.execute((RedisCallback) conn -> {
                StringRedisSerializer serializer = new StringRedisSerializer();
                byte[] data = conn.getSet(Objects.requireNonNull(serializer.serialize(key)), Objects.requireNonNull(serializer.serialize(value)));

                return Optional.ofNullable(data).map(serializer::deserialize).get();
            });
        } catch (Exception e) {
            log.error("getSet redis error, key: {}, error: {}", key, e.getMessage());
        }
        return oldValue;
    }

    @Override
    public synchronized boolean lock() throws InterruptedException {

        for (long timeout = timeoutMSECs; timeout > 0; timeout -= DEFAULT_ACQUIRY_RESOLUTION_MILLIS) {
            long expires = System.currentTimeMillis() + expireMSECs + 1;
            String expiresStr = String.valueOf(expires);
            if (this.setNX(this.lockKey, expiresStr)) {
                // lock acquired
                locked = true;
                return true;
            }

            String currentExpiresStr = this.get(lockKey); // redis里的时间
            if (currentExpiresStr != null && Long.parseLong(currentExpiresStr) < System.currentTimeMillis()) { // 锁超时

                String oldValueStr = getSet(lockKey, expiresStr);

                // 获取上一个锁到期时间，并设置现在的锁到期时间，
                // 只有一个线程才能获取上一个线上的设置时间，因为jedis.getSet是同步的
                if (oldValueStr != null && oldValueStr.equals(currentExpiresStr)) {
                    // 防止误删（覆盖，因为key是相同的）了他人的锁——这里达不到效果，这里值会被覆盖，但是因为什么相差了很少的时间，所以可以接受

                    // [分布式的情况下]:如过这个时候，多个线程恰好都到了这里，但是只有一个线程的设置值和当前值相同，他才有权利获取锁
                    // lock acquired
                    locked = true;
                    return true;
                }
            }

            /*
             *   延迟100 毫秒,  这里使用随机时间可能会好一点,可以防止饥饿进程的出现,即,当同时到达多个进程,
             *   只会有一个进程获得锁,其他的都用同样的频率进行尝试,后面有来了一些进程,也以同样的频率申请锁,这将可能导致前面来的锁得不到满足.
             *   使用随机的等待时间可以一定程度上保证公平性
             */
            Thread.sleep(DEFAULT_ACQUIRY_RESOLUTION_MILLIS);
        }

        return false;
    }

    @Override
    public synchronized void unlock() {
        if (locked) {
            redisTemplate.delete(lockKey);
            locked = false;
        }
    }
}
