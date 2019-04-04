package top.zhuyuncheng.distributedlock.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import top.zhuyuncheng.distributedlock.lock.DistributedLock;
import top.zhuyuncheng.distributedlock.lock.RedisLock;

import java.util.Map;

@RestController
@RequestMapping("/redis")
public class RedisController {

    @Autowired
    private StringRedisTemplate redisTemplate;

    int i = 1;
    boolean flag = false;

    @GetMapping
    public void test() {
        this.i = 1;
        DistributedLock lock = new RedisLock(redisTemplate, "ABKEY", 1000, 2000);

        new Thread(() -> {
            while (i < 1000) {
                if (!this.flag) {
                    try {
                        if (lock.lock()) {
                            Thread.sleep(100);
                            System.out.println(Thread.currentThread().getName() + "+-+" + i);
                            i++;
                            flag = true;
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }, "J").start();

        new Thread(() -> {
            while (i < 1000) {
                if (this.flag) {
                    try {
                        if (lock.lock()) {
                            Thread.sleep(100);
                            System.out.println(Thread.currentThread().getName() + "+-+" + i);
                            i++;
                            flag = false;
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } finally {
                        lock.unlock();
                    }
                }
            }
        }, "O").start();
    }

    @GetMapping("/{key}")
    public String get(@PathVariable("key") String key) {
        return redisTemplate.opsForValue().get(key);
    }


    @PostMapping("/setNX")
    public Boolean setNX(@RequestParam("key") String key, @RequestParam("value") String value) {
        RedisLock redisLock = new RedisLock(redisTemplate, key, 1000, 2000);
        boolean lock = false;
        try {
            lock = redisLock.lock();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            redisLock.unlock();
        }
        return lock;
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

}
