package top.zhuyuncheng.distributedlock.lock;

public interface DistributedLock {
    boolean lock() throws InterruptedException;

    void unlock();
}
