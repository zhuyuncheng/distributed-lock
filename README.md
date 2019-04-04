# 分布式锁的设计与实现

> 目前几乎很多大型网站及应用都是分布式部署的，在分布式系统中如何保证共享数据一致性的一直是一个比较重要的话题，所以就引入了分布式锁。
> 分布式锁与我们平常讲到的锁的原理是一样的，目的就是保证多个线程并发时，只有一个线程在同一时刻处理任务

## 分布式锁应该具备哪些条件

1. 互斥
1. 避免死锁
1. 可重入
1. 高可用 & 高性能

## 分布式锁的实现方式

- 基于数据库实现分布式锁
- 基于缓存（Redis等）实现分布式锁
- 基于Zookeeper实现分布式锁


## 一、基于数据库实现分布式锁

> 基于数据库实现的分布式锁的玩法有很多，可以像Redis保存/删除锁信息来获取/释放锁，也可以在表行中加入计数器一样的乐观锁

在数据库中维护一张表，包含锁的信息，比如任务名称，修改时间等字段，关键字段增加唯一索引，如果线程在获取锁时，`insert`成功则表示获取到锁，失败表示没有拿到锁，执行完任务以后删除掉对应的行，表示释放锁。

### 创建表

```sql
DROP TABLE IF EXISTS `task_lock`;

CREATE TABLE `task_lock` (
	`id` int(11) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
	`task_name` varchar(64) NOT NULL COMMENT '锁定的任务名',
	`desc` varchar(255) NOT NULL COMMENT '备注信息',
	`update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
	PRIMARY KEY (`id`),
	UNIQUE `uidx_task_name` USING BTREE (`task_name`)
) ENGINE = InnoDB AUTO_INCREMENT = 3 CHARSET = utf8 COMMENT '分布式锁表';
```

表结构如下：
```bash
mysql> desc task_lock;
+-------------+------------------+------+-----+-------------------+-----------------------------+
| Field       | Type             | Null | Key | Default           | Extra                       |
+-------------+------------------+------+-----+-------------------+-----------------------------+
| id          | int(11) unsigned | NO   | PRI | NULL              | auto_increment              |
| task_name   | varchar(64)      | NO   | UNI | NULL              |                             |
| desc        | varchar(255)     | NO   |     | NULL              |                             |
| update_time | timestamp        | NO   |     | CURRENT_TIMESTAMP | on update CURRENT_TIMESTAMP |
+-------------+------------------+------+-----+-------------------+-----------------------------+
4 rows in set (0.00 sec)
```
### INSERT数据获取锁

当我们要执行获取锁操作时，执行

```sql
INSERT INTO task_lock (task_name, desc) VALUES ('task_lock_01', 'xxx')
```
由于`task_name`字段是有唯一索引约束的，如果再次以相同的`task_name`插入，则抛出异常，表示获取锁失败

### DELETE数据释放锁

成功获取到锁以后，执行释放锁的操作

```sql
DELETE FROM task_lock WHERE task_name = 'task_lock_01'
```

> 数据库的实现相对简单，只是使用SQL实现，代码中不再描述。

## 二、基于Redis实现分布式锁

> 基于Redis实现的分布式锁主要是因为Redis是单进程单线程的，并且Redis具有很高的性能，操作的命令也比较简单，易实现

### Redis用到的命令解析 

1) **SETNX** : 将 key 的值设为 value ，当且仅当 key 不存在。若给定的 key 已经存在，则 SETNX 不做任何动作
    ```bash
    127.0.0.1:6379> setNX key value
    ```
    **返回值**:
    - 1: 成功
    - 0: 失败

2) **getSET** : 将给定 key 的值设为 value ，并返回 key 的旧值(old value)。当 key 存在但不是字符串类型时，返回一个错误。 
    ```bash
    127.0.0.1:6379> GETSET key value
    ```    
    
3) **get** : 当 key 不存在时，返回 nil ，否则，返回 key 的值。如果 key 不是字符串类型，那么返回一个错误
    ```bash
    127.0.0.1:6379> GET key
    ```
4) **del** : 删除指定的key
    ```bash
    127.0.0.1:6379> del key
    ```
### 避免死锁

> 当有一个线程得到锁之后，执行任务的过程中未释放锁，或者直接宕机了，这个时候我们需要引入锁超时的机制，来避免死锁的发生

我们可以通过锁的键对应的时间戳来判断这种情况是否发生了，如果当前的时间已经大于该值，说明该锁已失效，可以被重新使用

### 代码实现

```java
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

```

## 三、基于Zookeeper实现分布式锁

> zk(以下简称zk)本文只介绍一下实现的机制，因为`Curator`已经帮我们封装好了简洁的API，直接供我们调用，只需要关注`mutex.acquire()` 和 `mutex.release()`两个核心操作即可。

### 实现原理
![zk架构图](https://raw.githubusercontent.com/zhuyuncheng/distributed-lock/master/src/main/resources/static/zk.jpg)

假设锁空间的根节点为/lock

```bash
[zk: localhost:2181(CONNECTED) 2] ls /lock
[task-0000000002, task-0000000003, task-0000000001]
```

1) 通过zk创建`临时` `有序`的节点

```bash
create -s -e /lock/task- data
```

2) 客户端获取/lock下的子节点列表，判断自己创建的子节点是否为当前子节点列表中序号最小的子节点，如果是则认为获得锁，否则监听/lock的子节点变更消息，获得子节点变更通知后重复此步骤直至获得锁

3) 执行业务代码

4) 由于是临时节点，任务完成后，节点会自动删除释放锁，即使线程为释放锁或者宕机也会正常释放锁，避免死锁


### 封装`Curator`API代码
```java
package top.zhuyuncheng.distributedlock.lock;

import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.locks.InterProcessMutex;

import java.util.concurrent.TimeUnit;

@Slf4j
public class ZKLock implements DistributedLock {

    private CuratorFramework zkClient;

    private InterProcessMutex lock;

    /**
     * 锁超时时间，防止线程在入锁以后，无限的执行等待
     */
    private int expireMSECs = 60 * 1000;

    private TimeUnit unit = TimeUnit.MILLISECONDS;

    private String path;

    public ZKLock(CuratorFramework zkClient, String path) {
        this.path = "/lockPath/" + path;
        this.lock = new InterProcessMutex(zkClient, this.path);
    }

    public ZKLock(CuratorFramework zkClient, String path, int expireMSECs) {
        this(zkClient, path);
        this.expireMSECs = expireMSECs;
    }

    public ZKLock(CuratorFramework zkClient, String path, int expireMSECs, TimeUnit unit) {
        this(zkClient, path, expireMSECs);
        this.unit = unit;
    }

    public String getKey() {
        return path;
    }

    @Override
    public boolean lock() {
        try {
            return lock.acquire(expireMSECs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void unlock() {
        try {
            lock.release();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

```

## 总结

> - 基于数据库创建的分布式锁，受限于数据库的性能，而且数据库要避免单点部署；没有锁失效机制，可能会造成死锁，但是实现简单，不需要过多的依赖
> - Redis有很高的性能；对命令支持的也很好，实现起来相对比较方便
> - 具备高可用、可重入、阻塞锁特性，可解决失效死锁问题, 但是要维护zk集群，需要频繁的创建和删除节点
