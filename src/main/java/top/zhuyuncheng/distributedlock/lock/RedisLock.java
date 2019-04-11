package top.zhuyuncheng.distributedlock.lock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.Objects;

@Slf4j
public class RedisLock {

    private RedisTemplate redisTemplate;

    private static final int DEFAULT_ACQUIRY_RESOLUTION_MILLIS = 100;

    /**
     * 锁超时时间，防止线程在入锁以后，无限的执行等待
     */
    private int expireMSECs = 60 * 1000;

    /**
     * 锁等待时间，防止线程饥饿
     */
    private int timeoutMSECs = 10 * 1000;

    public RedisLock(RedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RedisLock(RedisTemplate redisTemplate, int expireMSECs) {
        this(redisTemplate);
        this.expireMSECs = expireMSECs;
    }

    public RedisLock(RedisTemplate redisTemplate, int expireMSECs, int timeoutMSECs) {
        this(redisTemplate, expireMSECs);
        this.timeoutMSECs = timeoutMSECs;
    }

    private boolean set(String key, String value, long seconds) {
        Boolean result = null;
        try {
            result = (boolean) redisTemplate.execute((RedisCallback) conn -> {
                StringRedisSerializer serializer = new StringRedisSerializer();
                Boolean success = conn.set(Objects.requireNonNull(serializer.serialize(key)), Objects.requireNonNull(serializer.serialize(value)), Expiration.milliseconds(seconds), RedisStringCommands.SetOption.SET_IF_ABSENT);
                conn.close();
                return success;
            });
        } catch (Exception e) {
            log.error("setNX redis error, key : {}, error: {}", key, e.getMessage());
        }
        return result != null && result;
    }

    public synchronized boolean lock(String key, String value) throws InterruptedException {

        for (long timeout = timeoutMSECs; timeout > 0; timeout -= DEFAULT_ACQUIRY_RESOLUTION_MILLIS) {
            if (this.set(key, value, expireMSECs)) {
                // lock acquired
                return true;
            }

            Thread.sleep(DEFAULT_ACQUIRY_RESOLUTION_MILLIS);
        }

        return false;
    }

    public synchronized boolean unlock(String lockKey, String value) {
        return (boolean) redisTemplate.execute((RedisCallback) conn -> {
            String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
            Boolean result = conn.eval(script.getBytes(), ReturnType.BOOLEAN, 1, lockKey.getBytes(), value.getBytes());
            return result;
        });
    }
}
