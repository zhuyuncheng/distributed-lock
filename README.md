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

6) **ttl**: 获取key的超时时间: 如果未设置超时时间返回-1; 如果key不存在或因为超时已经删除返回-2; 如果key存在且已设置超时时间则返回剩余的时间，单位为second

    ```bash
    127.0.0.1:6379> ttl key
    ```
        
### 避免死锁

> 当有一个线程得到锁之后，执行任务的过程中未释放锁，或者直接宕机了，这个时候我们需要引入锁超时的机制，来避免死锁的发生

通过 `SET key value [expiration EX seconds|PX milliseconds] [NX|XX]`命令加锁，并且设置锁的过期时间，例如：`set key value  PX 3000 NX`

### 释放锁

> 不能直接通过del释放锁，首先要判断是否为自己的锁，然后才可以释放, 通过执行lua脚本的方式，保证在原子操作内释放锁
```lua
if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end

```

### 代码实现

```java
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

## 最后骗一波关注

请扫码或搜索"小疯子呵呵哒"关注我的个人微信订阅号
<div align=center>
  <img src="https://zhuyuncheng.top/assets/weChatQRCode/2.jpeg" width = "500"/>
</div>
