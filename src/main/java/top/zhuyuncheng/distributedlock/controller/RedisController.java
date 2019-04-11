package top.zhuyuncheng.distributedlock.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import top.zhuyuncheng.distributedlock.lock.RedisLock;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/redis")
public class RedisController {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String KEY = "ABKEY";

    int i = 1;

    @GetMapping
    public void test() {
        this.i = 1;
        RedisLock lock = new RedisLock(redisTemplate, 60000, 2000);

        new Thread(() -> {
            while (i < 1000) {
                if (i % 2  == 1) {
                    final String uuid = this.getUUID();
                    try {
                        if (lock.lock(KEY, uuid)) {
                            System.out.println(Thread.currentThread().getName() + "获取到锁, uuid -> " + uuid);
                            Thread.sleep(30);
                            System.out.println(Thread.currentThread().getName() + "+-+" + i);
                            i++;
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        int num = i - 1;
                        if (num != 301) {
                            lock.unlock(KEY, uuid);
                            System.out.println(Thread.currentThread().getName() + "----" + num + "----释放锁, uuid -> " + uuid);
                        } else {
                            System.out.println(Thread.currentThread().getName() + ">>>>" + num + "<<<<未释放锁导致死锁了, uuid -> " + uuid);
                        }
                    }
                }
            }
        }, "J").start();

        new Thread(() -> {
            while (i < 1000) {
                if (i % 2 == 0) {
                    final String uuid = this.getUUID();
                    try {
                        if (lock.lock(KEY, uuid)) {
                            System.out.println(Thread.currentThread().getName() + "获取到锁, uuid -> " + uuid);
                            Thread.sleep(15);
                            System.out.println(Thread.currentThread().getName() + "+-+" + i);
                            i++;
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        int num = i - 1;
                        if (num != 600) {
                            lock.unlock(KEY, uuid);
                            System.out.println(Thread.currentThread().getName() + "----" + num + "----释放锁, uuid -> " + uuid);
                        } else {
                            System.out.println(Thread.currentThread().getName() + ">>>>>" + num + "<<<<<未释放锁导致死锁了, uuid -> " + uuid);
                        }
                    }
                }
            }
        }, "O").start();
    }

    @GetMapping("/{key}")
    public String get(@PathVariable("key") String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @PostMapping
    public Map<String, String> set(@RequestBody Map<String, String> map) {
        map.forEach((k, v) -> redisTemplate.opsForValue().set(k, v));
        return map;
    }

    @DeleteMapping
    public Boolean del(@RequestParam("key") String key) {
        return redisTemplate.delete(key);
    }

    private String getUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

}
