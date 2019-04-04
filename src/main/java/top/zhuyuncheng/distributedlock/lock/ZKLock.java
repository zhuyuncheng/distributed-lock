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
