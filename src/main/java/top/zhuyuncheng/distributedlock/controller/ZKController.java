package top.zhuyuncheng.distributedlock.controller;

import org.apache.curator.framework.CuratorFramework;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.zhuyuncheng.distributedlock.lock.DistributedLock;
import top.zhuyuncheng.distributedlock.lock.ZKLock;

import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/zk")
public class ZKController {
    private int i = 1;
    private boolean flag;

    @Autowired
    private CuratorFramework zkClient;

    @GetMapping
    public void test() {
        this.i = 1;
        DistributedLock lock = new ZKLock(zkClient, "JONUM", 1000, TimeUnit.MILLISECONDS);

        new Thread(() -> {
            while (i < 1000) {
                if (!this.flag) {
                    try {
                        if (lock.lock()) {
                            Thread.sleep(200);
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
                            Thread.sleep(200);
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
}
